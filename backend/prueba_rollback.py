import sqlite3
import tempfile

from pathlib import Path
from uuid import uuid4

import database
from main import ejecutar_operacion
from schemas import Transferencia


def estado_actual():
    """Captura saldos y registros para comparar antes y después."""
    with database.conexion() as db:
        return {
            "cuentas": [
                tuple(fila)
                for fila in db.execute(
                    "SELECT * FROM cuentas ORDER BY id"
                ).fetchall()
            ],
            "operaciones": [
                tuple(fila)
                for fila in db.execute(
                    "SELECT * FROM operaciones ORDER BY id"
                ).fetchall()
            ],
            "movimientos": [
                tuple(fila)
                for fila in db.execute(
                    "SELECT * FROM movimientos ORDER BY id"
                ).fetchall()
            ],
        }


def probar():
    database.inicializar_bd()

    with database.conexion(escritura=True) as db:
        # No se prueba login aquí: estos usuarios son datos de prueba.
        db.execute(
            """
            INSERT INTO usuarios (id, nombre, usuario, password_hash)
            VALUES (1, 'Origen', 'origen_test', 'no_utilizado')
            """
        )
        db.execute(
            """
            INSERT INTO usuarios (id, nombre, usuario, password_hash)
            VALUES (2, 'Destino', 'destino_test', 'no_utilizado')
            """
        )

        db.execute(
            """
            INSERT INTO cuentas (id, usuario_id, saldo_centavos)
            VALUES (1, 1, 10000)
            """
        )
        db.execute(
            """
            INSERT INTO cuentas (id, usuario_id, saldo_centavos)
            VALUES (2, 2, 0)
            """
        )

        # Fallo deliberado únicamente en esta base temporal.
        db.execute(
            """
            CREATE TRIGGER fallo_movimiento_receptor
            BEFORE INSERT ON movimientos
            WHEN NEW.tipo = 'TRANSFERENCIA_RECIBIDA'
            BEGIN
                SELECT RAISE(ABORT, 'FALLO_SIMULADO_RECEPTOR');
            END;
            """
        )

    datos = Transferencia(
        solicitud_id=uuid4(),
        monto_centavos=2500,
        cuenta_destino_id=2,
    )
    sesion = {"cuenta_id": 1}

    antes = estado_actual()

    try:
        ejecutar_operacion("TRANSFERENCIA", datos, sesion)
    except sqlite3.IntegrityError as error:
        assert "FALLO_SIMULADO_RECEPTOR" in str(error), str(error)
        print("Fallo intermedio provocado correctamente.")
    else:
        raise AssertionError("La transferencia debía fallar.")

    despues = estado_actual()

    assert despues == antes, (
        "El rollback no restauró todos los datos."
    )

    print("Saldo origen conservado: 10000 centavos")
    print("Saldo destino conservado: 0 centavos")
    print("Operaciones registradas: 0")
    print("Movimientos registrados: 0")
    print("ROLLBACK: APROBADO")

    # Quitamos el fallo y repetimos la misma solicitud.
    with database.conexion(escritura=True) as db:
        db.execute("DROP TRIGGER fallo_movimiento_receptor")

    respuesta = ejecutar_operacion(
        "TRANSFERENCIA", datos, sesion
    )

    confirmado = estado_actual()

    with database.conexion() as db:
        saldos = [
            fila["saldo_centavos"]
            for fila in db.execute(
                "SELECT saldo_centavos FROM cuentas ORDER BY id"
            ).fetchall()
        ]

    assert saldos == [7500, 2500]
    assert len(confirmado["operaciones"]) == 1
    assert len(confirmado["movimientos"]) == 2

    print("\nTransferencia posterior: APROBADA")
    print("Saldos finales:", saldos)

    # Un reintento confirmado no debe duplicar ningún registro.
    repetida = ejecutar_operacion(
        "TRANSFERENCIA", datos, sesion
    )

    assert repetida == respuesta
    assert estado_actual() == confirmado

    print("Reintento sin duplicación: APROBADO")
    print("\nRESULTADO: TODAS LAS COMPROBACIONES APROBADAS")


if __name__ == "__main__":
    ruta_original = database.DB_PATH

    with tempfile.TemporaryDirectory() as carpeta:
        try:
            database.DB_PATH = Path(carpeta) / "prueba.db"
            probar()
        finally:
            database.DB_PATH = ruta_original