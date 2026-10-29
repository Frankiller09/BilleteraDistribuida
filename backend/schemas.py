from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints


UsuarioTexto = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        to_lower=True,
        min_length=4,
        max_length=30,
        pattern=r"^[a-z0-9_]+$",
    ),
]

NombreTexto = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=2,
        max_length=80,
    ),
]

CedulaTexto = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=10,
        max_length=10,
        pattern=r"^[0-9]{10}$",
    ),
]

TipoCuenta = Literal["NORMAL", "AHORROS"]

AliasCuenta = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=2,
        max_length=40,
    ),
]

class ModeloBase(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Registro(ModeloBase):
    nombre: NombreTexto
    usuario: UsuarioTexto
    password: str = Field(min_length=8, max_length=128)

    cedula: CedulaTexto

    tipo_cuenta: TipoCuenta = "NORMAL"
    alias_cuenta: AliasCuenta = "Principal"


class Login(ModeloBase):
    usuario: UsuarioTexto
    password: str = Field(min_length=1, max_length=128)


class Operacion(ModeloBase):
    solicitud_id: UUID

    monto_centavos: int = Field(
        strict=True,
        gt=0,
        le=100_000_000,
    )

    # Si se omite, utiliza la primera cuenta por compatibilidad.
    cuenta_origen_id: int | None = Field(
        default=None,
        strict=True,
        gt=0,
    )


class ValidarDestinatario(ModeloBase):
    tipo: Literal["DEPOSITO", "TRANSFERENCIA"]
    numero_cuenta: Annotated[
        str,
        StringConstraints(
            strip_whitespace=True,
            min_length=10,
            max_length=10,
            pattern=r"^[0-9]{10}$",
        ),
    ]
    monto_centavos: int = Field(
        strict=True,
        gt=0,
        le=100_000_000,
    )
    cuenta_origen_id: int | None = Field(
        default=None,
        strict=True,
        gt=0,
    )


class ConfirmarDestino(ModeloBase):
    solicitud_id: UUID
    confirmacion_token: str = Field(min_length=20)


class PagoServicio(Operacion):
    servicio: Literal["AGUA", "LUZ", "INTERNET", "TELEFONIA"]
    referencia: Annotated[
        str,
        StringConstraints(
            strip_whitespace=True,
            min_length=3,
            max_length=60,
        ),
    ]

class CompletarCedula(ModeloBase):
    cedula: CedulaTexto

class NuevaCuenta(ModeloBase):
    tipo: TipoCuenta
    alias: AliasCuenta