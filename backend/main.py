from fastapi import FastAPI

app = FastAPI(
    title="API Billetera Distribuida",
    description="Backend del proyecto final de Aplicaciones Distribuidas.",
    version="0.1.0",
)


@app.get("/inicio", tags=["Estado"])
def inicio():
    return {
        "estado": "activo",
        "mensaje": "Backend de Billetera Distribuida funcionando",
    }