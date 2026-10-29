package com.franco.billeteradistribuida

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

class BilleteraViewModel : ViewModel() {

    private val api = BilleteraApi()
    private var token: String? = null

    var cuenta by mutableStateOf<Cuenta?>(null)
        private set

    var cargando by mutableStateOf(false)
        private set

    var mensaje by mutableStateOf<String?>(null)
        private set

    private fun ejecutar(accion: suspend () -> Unit) {
        if (cargando) return

        cargando = true
        mensaje = null

        viewModelScope.launch {
            try {
                accion()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (error.codigo == 401) {
                    token = null
                    cuenta = null
                }
                mensaje = error.message
            } catch (error: IOException) {
            mensaje = "Error de conexión: ${error.message}"
            } catch (error: Exception) {
                mensaje = "No se pudo procesar la respuesta del servidor."
            } finally {
                cargando = false
            }
        }
    }

    fun ingresar(usuario: String, password: String) {
        if (usuario.isBlank() || password.isBlank()) {
            mensaje = "Completa usuario y contraseña."
            return
        }

        ejecutar {
            val nuevoToken = api.login(
                usuario.trim().lowercase(),
                password
            )

            val nuevaCuenta = api.obtenerCuenta(nuevoToken)

            token = nuevoToken
            cuenta = nuevaCuenta
        }
    }

    fun registrar(
        nombre: String,
        usuario: String,
        password: String
    ) {
        val usuarioLimpio = usuario.trim().lowercase()

        if (nombre.trim().length !in 2..80) {
            mensaje = "El nombre debe tener entre 2 y 80 caracteres."
            return
        }

        if (!Regex("^[a-z0-9_]{4,30}$").matches(usuarioLimpio)) {
            mensaje = "Usuario: 4 a 30 letras, números o guion bajo."
            return
        }

        if (password.length !in 8..128) {
            mensaje = "La contraseña debe tener entre 8 y 128 caracteres."
            return
        }

        ejecutar {
            api.registrar(nombre.trim(), usuarioLimpio, password)
            mensaje = "Cuenta creada. Pulsa «Ya tengo cuenta» e inicia sesión."
        }
    }

    fun actualizarSaldo() {
        val actual = token ?: return

        ejecutar {
            cuenta = api.obtenerCuenta(actual)
            mensaje = "Saldo actualizado desde el servidor."
        }
    }

    fun cerrarSesion() {
        val actual = token ?: return

        ejecutar {
            api.logout(actual)
            token = null
            cuenta = null
            mensaje = "Sesión cerrada."
        }
    }

    fun limpiarMensaje() {
        mensaje = null
    }
}