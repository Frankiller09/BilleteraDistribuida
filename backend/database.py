import sqlite3
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