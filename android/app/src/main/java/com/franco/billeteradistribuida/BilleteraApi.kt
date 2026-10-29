package com.franco.billeteradistribuida

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

// El dinero viaja siempre en centavos; el número de cuenta siempre como texto.
data class Cuenta(val id: Long, val nombre: String, val usuario: String,
                  val saldoCentavos: Long, val numero: String, val tipo: String, val alias: String)
data class Perfil(val nombre: String, val usuario: String, val cedula: String?)
data class Servicio(val codigo: String, val nombre: String, val referencia: String)
data class Movimiento(val id: Long, val tipo: String, val monto: Long, val saldo: Long,
                      val detalle: String, val fecha: String, val operacion: String, val ingreso: Boolean,
                      val numero: String = "")
data class PaginaMovimientos(val items: List<Movimiento>, val siguiente: Long?)
data class DiaResumen(val fecha: String, val ingresos: Long, val egresos: Long)
data class Resumen(val ingresos: Long, val egresos: Long, val cantidad: Int, val dias: List<DiaResumen>)
data class Beneficiario(val token: String, val titular: String, val numero: String,
                        val monto: Long, val expira: Long)
class ApiException(val codigo: Int, message: String) : IOException(message)

class BilleteraApi {
    companion object {
        const val BASE_URL = "http://10.0.2.2:8001"
    }
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    // Las consultas pueden recuperarse de una conexión cerrada.
    private val clienteConsultas = cliente.newBuilder()
        .retryOnConnectionFailure(true)
        .build()
    private val clienteWs = cliente.newBuilder().callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val jsonTipo = "application/json; charset=utf-8".toMediaType()

    private suspend fun solicitar(ruta: String, token: String? = null,
                                  datos: JSONObject? = null, metodo: String = if (datos == null) "GET" else "POST"): Any =
        withContext(Dispatchers.IO) {
            val peticion = Request.Builder().url("$BASE_URL$ruta")
            token?.let { peticion.header("Authorization", "Bearer $it") }
            peticion.method(metodo, datos?.toString()?.toRequestBody(jsonTipo))
            val clientePeticion = if (metodo == "GET") {
                clienteConsultas
            } else {
                cliente
            }
            clientePeticion.newCall(peticion.build()).execute().use { respuesta ->
                val texto = respuesta.body?.string().orEmpty()
                if (!respuesta.isSuccessful) {
                    val detalle = runCatching { JSONObject(texto).opt("detail") }.getOrNull()
                    val mensaje = when (detalle) {
                        is String -> detalle
                        is JSONArray -> (0 until detalle.length()).joinToString("\n") { i ->
                            val error = detalle.getJSONObject(i)
                            "${error.optJSONArray("loc")?.join(" / ")}: ${error.optString("msg")}"
                        }
                        else -> "Solicitud rechazada: HTTP ${respuesta.code}"
                    }
                    throw ApiException(respuesta.code, mensaje)
                }
                if (texto.isBlank()) JSONObject() else JSONTokener(texto).nextValue()
            }
        }
    suspend fun registrar(nombre: String, usuario: String, password: String,
                          cedula: String, tipo: String, alias: String) {
        solicitar("/auth/registro", datos = JSONObject().put("nombre", nombre)
            .put("usuario", usuario).put("password", password).put("cedula", cedula)
            .put("tipo_cuenta", tipo).put("alias_cuenta", alias))
    }
    suspend fun login(usuario: String, password: String): String =
        (solicitar("/auth/login", datos = JSONObject().put("usuario", usuario)
            .put("password", password)) as JSONObject).getString("access_token")
    suspend fun perfil(token: String): Perfil {
        val j = solicitar("/usuarios/me", token) as JSONObject
        return Perfil(j.getString("nombre"), j.getString("usuario"),
            if (j.isNull("cedula")) null else j.getString("cedula"))
    }
    suspend fun completarCedula(token: String, cedula: String) {
        solicitar("/usuarios/me/cedula", token, JSONObject().put("cedula", cedula), "PATCH")
    }
    suspend fun cuentas(token: String, perfil: Perfil): List<Cuenta> {
        val r = solicitar("/cuentas", token)
        val lista = when (r) {
            is JSONArray -> r
            is JSONObject -> r.optJSONArray("cuentas") ?: r.getJSONArray("items")
            else -> throw IOException("Formato de cuentas no reconocido")
        }
        return (0 until lista.length()).map { i ->
            val j = lista.getJSONObject(i)
            Cuenta(j.getLong("cuenta_id"), perfil.nombre, perfil.usuario,
                j.getLong("saldo_centavos"), j.getString("numero_cuenta"),
                j.getString("tipo"), j.getString("alias"))
        }
    }
    suspend fun crearCuenta(token: String, tipo: String, alias: String) {
        solicitar("/cuentas", token, JSONObject().put("tipo", tipo).put("alias", alias))
    }
    suspend fun servicios(token: String): List<Servicio> {
        val a = (solicitar("/servicios", token) as JSONObject).getJSONArray("servicios")
        return (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Servicio(it.getString("codigo"), it.getString("nombre"), it.getString("referencia")) } }
    }
    suspend fun validar(token: String, tipo: String, numero: String, monto: Long,
                        origen: Long?): Beneficiario {
        val datos = JSONObject().put("tipo", tipo).put("numero_cuenta", numero).put("monto_centavos", monto)
        origen?.let { datos.put("cuenta_origen_id", it) }
        val j = solicitar("/beneficiarios/validar", token, datos) as JSONObject
        return Beneficiario(j.getString("confirmacion_token"), j.getString("titular"),
            j.getString("numero_cuenta"), j.getLong("monto_centavos"), j.getLong("expira"))
    }
    suspend fun operar(token: String, ruta: String, cuerpo: String): JSONObject =
        solicitar(ruta, token, JSONObject(cuerpo)) as JSONObject
    suspend fun historial(token: String, cuenta: Long, desde: String, hasta: String,
                          tipo: String, buscar: String, antes: Long? = null): PaginaMovimientos {
        val url = okhttp3.HttpUrl.Builder().scheme("http").host("localhost")
            .addQueryParameter("desde", desde).addQueryParameter("hasta", hasta)
            .addQueryParameter("limite", "20")
        if (tipo.isNotEmpty()) url.addQueryParameter("tipo", tipo)
        if (buscar.isNotBlank()) url.addQueryParameter("buscar", buscar)
        antes?.let { url.addQueryParameter("antes_de", it.toString()) }
        val j = solicitar("/cuentas/$cuenta/movimientos?${url.build().encodedQuery}", token) as JSONObject
        val a = j.getJSONArray("items")
        return PaginaMovimientos((0 until a.length()).map { movimiento(a.getJSONObject(it)) },
            if (j.isNull("siguiente_antes_de")) null else j.getLong("siguiente_antes_de"))
    }
    private fun movimiento(j: JSONObject) = Movimiento(j.getLong("id"), j.getString("tipo"),
        j.getLong("monto_centavos"), j.getLong("saldo_resultante_centavos"), j.getString("detalle"),
        j.getString("creado"), j.getString("operacion_id"), j.getString("direccion") == "INGRESO",
        j.optString("numero_cuenta"))
    suspend fun comprobante(token: String, id: Long) = movimiento(solicitar("/movimientos/$id", token) as JSONObject)
    suspend fun resumen(token: String, cuenta: Long, desde: String, hasta: String): Resumen {
        val j = solicitar("/cuentas/$cuenta/resumen?desde=$desde&hasta=$hasta", token) as JSONObject
        val a = j.getJSONArray("por_dia")
        return Resumen(j.getLong("ingresos_centavos"), j.getLong("egresos_centavos"),
            j.getInt("cantidad_movimientos"), (0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                DiaResumen(d.getString("fecha"), d.getLong("ingresos_centavos"), d.getLong("egresos_centavos")) })
    }
    suspend fun logout(token: String) { solicitar("/auth/logout", token, JSONObject()) }
    fun conectar(token: String, listener: WebSocketListener): WebSocket = clienteWs.newWebSocket(
        Request.Builder().url(BASE_URL.replaceFirst("http", "ws") + "/ws")
            .header("Authorization", "Bearer $token").build(), listener)
}
