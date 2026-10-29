import asyncio
from collections import Counter
from uuid import uuid4

import httpx


BASE_URL = "http://127.0.0.1:8001"


async def main():
    usuario = f"prueba_{uuid4().hex[:12]}"
    password = "PruebaConcurrencia2026!"

    async with httpx.AsyncClient(
        base_url=BASE_URL,
        timeout=60,
    ) as cliente:

        # Una cuenta nueva evita alterar las cuentas de demostración.
        respuesta = await cliente.post(
            "/auth/registro",
            json={
                "nombre": "Prueba de concurrencia",
                "usuario": usuario,
                "password": password,
            },
        )
        respuesta.raise_for_status()

        respuesta = await cliente.post(
            "/auth/login",
            json={
                "usuario": usuario,
                "password": password,
            },
        )
        respuesta.raise_for_status()

        cliente.headers["Authorization"] = (
            f"Bearer {respuesta.json()['access_token']}"
        )

        # Saldo inicial: $50.
        respuesta = await cliente.post(
            "/operaciones/deposito",
            json={
                "solicitud_id": str(uuid4()),
                "monto_centavos": 5000,
            },
        )
        respuesta.raise_for_status()

        salida = asyncio.Event()

        async def retirar():
            await salida.wait()

            return await cliente.post(
                "/operaciones/retiro",
                json={
                    "solicitud_id": str(uuid4()),
                    "monto_centavos": 1000,
                },
            )

        # 20 solicitudes distintas intentan retirar $10 cada una.
        tareas = [
            asyncio.create_task(retirar())
            for _ in range(20)
        ]

        salida.set()
        respuestas = await asyncio.gather(*tareas)

        codigos = Counter(r.status_code for r in respuestas)

        respuesta = await cliente.get("/cuentas/me")
        respuesta.raise_for_status()
        saldo = respuesta.json()["saldo_centavos"]

        respuesta = await cliente.get("/movimientos")
        respuesta.raise_for_status()
        movimientos = respuesta.json()

        retiros = [
            m for m in movimientos
            if m["tipo"] == "RETIRO"
        ]

        rechazos_correctos = sum(
            r.status_code == 409
            and r.json().get("detail") == "Saldo insuficiente."
            for r in respuestas
        )

        print("\nPRUEBA DE CONCURRENCIA")
        print("Usuario de prueba:", usuario)
        print("Saldo inicial: 5000 centavos")
        print("Solicitudes: 20 retiros de 1000 centavos")
        print("Respuestas HTTP:", dict(codigos))
        print("Saldo final:", saldo)
        print("Retiros registrados:", len(retiros))
        print("Movimientos totales:", len(movimientos))

        assert codigos[200] == 5, "Deben aprobarse 5 retiros."
        assert rechazos_correctos == 15, "Deben rechazarse 15 por saldo."
        assert saldo == 0, "El saldo final debe ser cero."
        assert len(retiros) == 5, "Deben existir 5 retiros."
        assert len(movimientos) == 6, "Debe haber 1 depósito y 5 retiros."
        assert sum(m["monto_centavos"] for m in retiros) == 5000
        assert all(
            m["saldo_resultante_centavos"] >= 0
            for m in movimientos
        ), "Ningún movimiento debe dejar saldo negativo."

        print("\nRESULTADO: PRUEBA APROBADA")


if __name__ == "__main__":
    asyncio.run(main())