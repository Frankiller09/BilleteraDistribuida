import sqlite3
import secrets
from contextlib import contextmanager
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent / "billetera.db"


@contextmanager
def conexion(escritura=False):
    db = sqlite3.connect(DB_PATH, timeout=15)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys = ON")

    try:
        if escritura:
            db.execute("BEGIN IMMEDIATE")

        yield db
        db.commit()

    except Exception:
        db.rollback()
        raise

    finally:
        db.close()


def inicializar_bd():
    with conexion() as db:
        db.execute("PRAGMA journal_mode = WAL")

        db.executescript("""
            CREATE TABLE IF NOT EXISTS usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                nombre TEXT NOT NULL,
                usuario TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                creado TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS cuentas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                usuario_id INTEGER NOT NULL UNIQUE,
                saldo_centavos INTEGER NOT NULL DEFAULT 0
                    CHECK (
                        typeof(saldo_centavos) = 'integer'
                        AND saldo_centavos >= 0
                    ),
                FOREIGN KEY (usuario_id) REFERENCES usuarios(id)
            );

            CREATE TABLE IF NOT EXISTS sesiones (
                token_hash TEXT PRIMARY KEY,
                usuario_id INTEGER NOT NULL,
                expira INTEGER NOT NULL,
                FOREIGN KEY (usuario_id) REFERENCES usuarios(id)
            );

            CREATE TABLE IF NOT EXISTS operaciones (
                id TEXT PRIMARY KEY,
                cuenta_id INTEGER NOT NULL,
                solicitud_id TEXT NOT NULL,
                datos TEXT NOT NULL,
                respuesta TEXT NOT NULL,
                creado TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (cuenta_id, solicitud_id),
                FOREIGN KEY (cuenta_id) REFERENCES cuentas(id)
            );

            CREATE TABLE IF NOT EXISTS movimientos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operacion_id TEXT NOT NULL,
                cuenta_id INTEGER NOT NULL,
                tipo TEXT NOT NULL,
                monto_centavos INTEGER NOT NULL CHECK (monto_centavos > 0),
                saldo_resultante_centavos INTEGER NOT NULL,
                detalle TEXT NOT NULL,
                creado TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (operacion_id) REFERENCES operaciones(id),
                FOREIGN KEY (cuenta_id) REFERENCES cuentas(id)
            );

            CREATE INDEX IF NOT EXISTS idx_movimientos_cuenta
            ON movimientos(cuenta_id, id);
        """)
    

def migrar_cedula():
    with conexion(escritura=True) as db:
        columnas = {
            fila["name"]
            for fila in db.execute("PRAGMA table_info(usuarios)")
        }

        if "cedula" not in columnas:
            db.execute("""
                ALTER TABLE usuarios
                ADD COLUMN cedula TEXT
                CHECK (
                    cedula IS NULL
                    OR (
                        length(cedula) = 10
                        AND cedula NOT GLOB '*[^0-9]*'
                    )
                )
            """)

        db.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_usuarios_cedula
            ON usuarios(cedula)
        """)

def generar_numero_cuenta(db, tabla="cuentas"):
    # Solo se permiten tablas internas conocidas.
    if tabla not in {"cuentas", "cuentas_nuevas"}:
        raise ValueError("Tabla no permitida.")

    for _ in range(100):
        numero = str(1_000_000_000 + secrets.randbelow(9_000_000_000))

        existe = db.execute(
            f"SELECT 1 FROM {tabla} WHERE numero_cuenta = ?",
            (numero,),
        ).fetchone()

        if existe is None:
            return numero

    raise RuntimeError("No se pudo generar un número disponible.")


def crear_cuenta_bd(db, usuario_id, tipo, alias):
    # Se llama dentro de conexion(escritura=True).
    numero = generar_numero_cuenta(db)

    cursor = db.execute(
        """
        INSERT INTO cuentas (
            usuario_id, numero_cuenta, tipo, alias
        )
        VALUES (?, ?, ?, ?)
        """,
        (usuario_id, numero, tipo, alias),
    )

    fila = db.execute(
        """
        SELECT id AS cuenta_id, numero_cuenta,
               tipo, alias, saldo_centavos
        FROM cuentas
        WHERE id = ?
        """,
        (cursor.lastrowid,),
    ).fetchone()

    return dict(fila)


def migrar_multicuenta():
    db = sqlite3.connect(DB_PATH, timeout=15)
    db.row_factory = sqlite3.Row

    try:
        columnas = {
            fila["name"]
            for fila in db.execute("PRAGMA table_info(cuentas)")
        }

        if {"numero_cuenta", "tipo", "alias"} <= columnas:
            return

        if columnas != {"id", "usuario_id", "saldo_centavos"}:
            raise RuntimeError(
                "El esquema de cuentas no es el esperado. "
                "No se realizó la migración."
            )

        # Evita eliminar índices o triggers personalizados sin revisarlos.
        objetos = db.execute(
            """
            SELECT name FROM sqlite_master
            WHERE tbl_name = 'cuentas'
              AND type IN ('index', 'trigger')
              AND sql IS NOT NULL
            """
        ).fetchall()

        if objetos:
            raise RuntimeError(
                "Hay índices o triggers personalizados en cuentas. "
                "Revisarlos antes de migrar."
            )

        # Se desactiva solo en esta conexión, antes de la transacción.
        db.execute("PRAGMA foreign_keys = OFF")
        db.execute("BEGIN IMMEDIATE")

        anteriores = db.execute(
            "SELECT * FROM cuentas ORDER BY id"
        ).fetchall()

        secuencia = db.execute(
            "SELECT seq FROM sqlite_sequence WHERE name = 'cuentas'"
        ).fetchone()

        db.execute("""
            CREATE TABLE cuentas_nuevas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                usuario_id INTEGER NOT NULL,
                numero_cuenta TEXT NOT NULL UNIQUE
                    CHECK (
                        length(numero_cuenta) = 10
                        AND numero_cuenta NOT GLOB '*[^0-9]*'
                    ),
                tipo TEXT NOT NULL
                    CHECK (tipo IN ('NORMAL', 'AHORROS')),
                alias TEXT NOT NULL
                    CHECK (length(trim(alias)) BETWEEN 2 AND 40),
                saldo_centavos INTEGER NOT NULL DEFAULT 0
                    CHECK (
                        typeof(saldo_centavos) = 'integer'
                        AND saldo_centavos >= 0
                    ),
                FOREIGN KEY (usuario_id) REFERENCES usuarios(id)
            )
        """)

        for cuenta in anteriores:
            numero = generar_numero_cuenta(db, "cuentas_nuevas")

            db.execute(
                """
                INSERT INTO cuentas_nuevas (
                    id, usuario_id, numero_cuenta,
                    tipo, alias, saldo_centavos
                )
                VALUES (?, ?, ?, 'NORMAL', 'Principal', ?)
                """,
                (
                    cuenta["id"],
                    cuenta["usuario_id"],
                    numero,
                    cuenta["saldo_centavos"],
                ),
            )

        # Comprueba que los datos originales se copiaron exactamente.
        conservados = db.execute(
            """
            SELECT id, usuario_id, saldo_centavos
            FROM cuentas_nuevas ORDER BY id
            """
        ).fetchall()

        if [tuple(fila) for fila in anteriores] != [
            tuple(fila) for fila in conservados
        ]:
            raise RuntimeError("Los datos copiados no coinciden.")

        db.execute("DROP TABLE cuentas")
        db.execute("ALTER TABLE cuentas_nuevas RENAME TO cuentas")

        if secuencia is not None:
            db.execute(
                """
                UPDATE sqlite_sequence
                SET seq = MAX(seq, ?)
                WHERE name = 'cuentas'
                """,
                (secuencia["seq"],),
            )

        db.execute("""
            CREATE INDEX idx_cuentas_usuario
            ON cuentas(usuario_id)
        """)

        if db.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("Se detectaron referencias inválidas.")

        db.commit()
        print(
            f"Multicuenta: {len(anteriores)} cuentas migradas; "
            "IDs, propietarios y saldos conservados."
        )

    except Exception:
        db.rollback()
        raise

    finally:
        db.close()


def migrar_confirmaciones():
    with conexion(escritura=True) as db:
        columnas = {
            fila["name"]
            for fila in db.execute("PRAGMA table_info(operaciones)")
        }
        if "actor_id" not in columnas:
            db.execute("""
                ALTER TABLE operaciones
                ADD COLUMN actor_id INTEGER REFERENCES usuarios(id)
            """)
        db.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_operaciones_actor
            ON operaciones(actor_id, solicitud_id)
        """)

        columnas = {
            fila["name"]
            for fila in db.execute("PRAGMA table_info(movimientos)")
        }
        if "metadatos" not in columnas:
            db.execute("""
                ALTER TABLE movimientos
                ADD COLUMN metadatos TEXT NOT NULL DEFAULT '{}'
            """)

        db.execute("""
            CREATE TABLE IF NOT EXISTS confirmaciones (
                token_hash TEXT PRIMARY KEY,
                sesion_hash TEXT NOT NULL,
                usuario_id INTEGER NOT NULL REFERENCES usuarios(id),
                tipo TEXT NOT NULL,
                origen_id INTEGER REFERENCES cuentas(id),
                destino_id INTEGER NOT NULL REFERENCES cuentas(id),
                numero_cuenta TEXT NOT NULL,
                titular TEXT NOT NULL,
                monto_centavos INTEGER NOT NULL
                    CHECK (monto_centavos > 0),
                expira INTEGER NOT NULL,
                usada INTEGER NOT NULL DEFAULT 0
            )
        """)