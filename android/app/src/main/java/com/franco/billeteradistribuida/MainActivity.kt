package com.franco.billeteradistribuida

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

private val Marino = Color(0xFF142D4E)
private val VerdeMarca = Color(0xFFA7BC58)
private val VerdeTarjeta = Color(0xFFDCEBB5)
private val Texto = Color(0xFF172026)
private val Gris = Color(0xFF667085)

fun dinero(centavos: Long): String =
    NumberFormat.getCurrencyInstance(Locale.US)
        .format(BigDecimal.valueOf(centavos, 2))

class MainActivity : ComponentActivity() {

    private val permisoRedLocal =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                concedido ->

            if (!concedido) {
                Toast.makeText(
                    this,
                    "El acceso a la red local permite conectar con el servidor de tu PC.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 37) {
            val permiso = "android.permission.ACCESS_LOCAL_NETWORK"

            if (
                ContextCompat.checkSelfPermission(this, permiso)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permisoRedLocal.launch(permiso)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Marino,
                    secondary = VerdeMarca,
                    background = Color.White,
                    surface = Color.White,
                    onSurface = Texto,
                    onBackground = Texto
                )
            ) {
                AppBilletera()
            }
        }
    }
}

@Composable
fun AppBilletera(vm: BilleteraViewModel = viewModel()) {
    var pantalla by rememberSaveable {
        mutableStateOf("BIENVENIDA")
    }

    val cuenta = vm.cuenta

    LaunchedEffect(cuenta?.id) {
        if (cuenta != null) {
            pantalla = "BIENVENIDA"
        }
    }

    if (cuenta != null) {
        InicioCuenta(cuenta, vm)
    } else {
        BackHandler(
            enabled = pantalla != "BIENVENIDA" && !vm.cargando
        ) {
            vm.limpiarMensaje()
            pantalla = "BIENVENIDA"
        }

        PantallaAcceso(
            pantalla = pantalla,
            vm = vm,
            navegar = {
                vm.limpiarMensaje()
                pantalla = it
            }
        )
    }
}

@Composable
private fun LogoFs(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(18.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.logo_fs),
            contentDescription = "FS Billetera Virtual",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(62.dp)
        )
    }
}

@Composable
private fun PantallaAcceso(
    pantalla: String,
    vm: BilleteraViewModel,
    navegar: (String) -> Unit
) {
    val bienvenida = pantalla == "BIENVENIDA"
    val registro = pantalla == "REGISTRO"

    var nombre by rememberSaveable { mutableStateOf("") }
    var usuario by rememberSaveable { mutableStateOf("") }
    var password by remember(pantalla) { mutableStateOf("") }
    var visible by remember(pantalla) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF063923))
    ) {
        Image(
            painter = painterResource(R.drawable.fondo_acceso),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
        ) {
            val altoDisponible = maxHeight

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = altoDisponible)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(12.dp))

                    LogoFs(Modifier.width(220.dp))

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = "BILLETERA\nVIRTUAL",
                        color = Color.White,
                        fontSize = if (bienvenida) 38.sp else 28.sp,
                        lineHeight = if (bienvenida) 42.sp else 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Tu dinero, a tu alcance.",
                        color = Color(0xFFDCE7DF),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (bienvenida) {
                        BotonAcceso("CREAR CUENTA") {
                            navegar("REGISTRO")
                        }

                        BotonAcceso("INICIAR SESIÓN") {
                            navegar("LOGIN")
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            color = Color.White
                        ) {
                            Column(
                                modifier = Modifier.padding(22.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = if (registro) "Crea tu cuenta"
                                    else "Bienvenido de nuevo",
                                    color = Texto,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = if (registro)
                                        "Completa tus datos para comenzar."
                                    else
                                        "Ingresa con tu usuario y contraseña.",
                                    color = Gris,
                                    fontSize = 14.sp
                                )

                                if (registro) {
                                    OutlinedTextField(
                                        value = nombre,
                                        onValueChange = { nombre = it },
                                        label = { Text("Nombre completo") },
                                        singleLine = true,
                                        enabled = !vm.cargando,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                OutlinedTextField(
                                    value = usuario,
                                    onValueChange = { usuario = it },
                                    label = { Text("Usuario") },
                                    singleLine = true,
                                    enabled = !vm.cargando,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Contraseña") },
                                    singleLine = true,
                                    enabled = !vm.cargando,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (visible)
                                        VisualTransformation.None
                                    else
                                        PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password
                                    ),
                                    trailingIcon = {
                                        TextButton(
                                            onClick = { visible = !visible }
                                        ) {
                                            Text(
                                                if (visible) "Ocultar"
                                                else "Ver"
                                            )
                                        }
                                    }
                                )

                                Button(
                                    onClick = {
                                        if (registro) {
                                            vm.registrar(
                                                nombre, usuario, password
                                            )
                                        } else {
                                            vm.ingresar(usuario, password)
                                        }
                                    },
                                    enabled = !vm.cargando,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 54.dp)
                                ) {
                                    Text(
                                        if (registro) "Crear cuenta"
                                        else "Ingresar"
                                    )
                                }

                                if (vm.cargando) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                vm.mensaje?.let {
                                    Text(
                                        text = it,
                                        color = Marino,
                                        fontSize = 14.sp
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        navegar(
                                            if (registro) "LOGIN"
                                            else "REGISTRO"
                                        )
                                    },
                                    enabled = !vm.cargando,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (registro) "Ya tengo cuenta"
                                        else "Crear una cuenta"
                                    )
                                }
                            }
                        }

                        TextButton(
                            onClick = { navegar("BIENVENIDA") },
                            enabled = !vm.cargando,
                            modifier = Modifier.align(
                                Alignment.CenterHorizontally
                            )
                        ) {
                            Text("Volver al inicio", color = Color.White)
                        }
                    }
                }

                Text(
                    text = "© 2026 Franco Pillajo · Sebastián Lojan\n" +
                            "Todos los derechos reservados.",
                    color = Color(0xFFDCE7DF),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun BotonAcceso(
    texto: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = VerdeMarca,
            contentColor = Color(0xFF07351F)
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
    ) {
        Text(
            text = texto,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun InicioCuenta(
    cuenta: Cuenta,
    vm: BilleteraViewModel
) {
    Scaffold(containerColor = Color.White) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_fs),
                    contentDescription = "FS",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(86.dp).height(32.dp)
                )

                Text(
                    text = "MI BILLETERA",
                    color = Marino,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Bienvenido,", color = Gris, fontSize = 15.sp)

                Text(
                    text = cuenta.nombre,
                    color = Texto,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                color = VerdeTarjeta,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(26.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "SALDO DISPONIBLE",
                        color = Marino,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = dinero(cuenta.saldoCentavos),
                        color = Texto,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold
                    )

                    HorizontalDivider(
                        color = Marino.copy(alpha = 0.15f)
                    )

                    Text(
                        text = "Cuenta #${cuenta.id}",
                        color = Texto,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "@${cuenta.usuario}",
                        color = Marino,
                        fontSize = 14.sp
                    )
                }
            }

            Text(
                text = "Último saldo consultado en el servidor.",
                color = Gris,
                fontSize = 13.sp
            )

            Button(
                onClick = { vm.actualizarSaldo() },
                enabled = !vm.cargando,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                Text("Actualizar saldo")
            }

            if (vm.cargando) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            vm.mensaje?.let {
                Text(it, color = Marino, fontSize = 14.sp)
            }

            OutlinedButton(
                onClick = { vm.cerrarSesion() },
                enabled = !vm.cargando,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cerrar sesión")
            }
        }
    }
}