package com.franco.billeteradistribuida

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Cuenta(
    val id: Long,
    val nombre: String,
    val usuario: String,
    val saldoCentavos: Long
)

class ApiException(
    val codigo: Int,
    message: String
) : IOException(message)

class BilleteraApi {

    companion object {
        const val BASE_URL = "http://10.0.2.2:8001"
    }

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonTipo = "application/json; charset=utf-8".toMediaType()

    private suspend fun solicitar(
        ruta: String,
        datos: JSONObject? = null,
        token: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {

        val constructor = Request.Builder()
            .url("$BASE_URL$ruta")

        if (token != null) {
            constructor.header("Authorization", "Bearer $token")
        }

        if (datos != null) {
            constructor.post(
                datos.toString().toRequestBody(jsonTipo)
            )
        }

        cliente.newCall(constructor.build()).execute().use { respuesta ->
            val texto = respuesta.body?.string().orEmpty()

            if (!respuesta.isSuccessful) {
                val detalle = runCatching {
                    JSONObject(texto).opt("detail")
                }.getOrNull()

                val mensaje = when (detalle) {
                    is String -> detalle
                    else -> "Solicitud rechazada: HTTP ${respuesta.code}"
                }

                throw ApiException(respuesta.code, mensaje)
            }

            JSONObject(texto)
        }
    }

    suspend fun registrar(
        nombre: String,
        usuario: String,
        password: String
    ) {
        solicitar(
            "/auth/registro",
            JSONObject()
                .put("nombre", nombre)
                .put("usuario", usuario)
                .put("password", password)
        )
    }

    suspend fun login(usuario: String, password: String): String {
        val respuesta = solicitar(
            "/auth/login",
            JSONObject()
                .put("usuario", usuario)
                .put("password", password)
        )

        return respuesta.getString("access_token")
    }

    suspend fun obtenerCuenta(token: String): Cuenta {
        val respuesta = solicitar(
            ruta = "/cuentas/me",
            token = token
        )

        return Cuenta(
            id = respuesta.getLong("cuenta_id"),
            nombre = respuesta.getString("nombre"),
            usuario = respuesta.getString("usuario"),
            saldoCentavos = respuesta.getLong("saldo_centavos")
        )
    }

    suspend fun logout(token: String) {
        solicitar(
            ruta = "/auth/logout",
            datos = JSONObject(),
            token = token
        )
    }
}