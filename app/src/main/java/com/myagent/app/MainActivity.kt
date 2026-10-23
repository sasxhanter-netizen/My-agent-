package com.myagent.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00B4D8),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onSurface = Color.White
                )
            ) {
                AgentScreen(this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(activity: Activity) {
    val context = activity
    val prefs = remember { context.getSharedPreferences("agent", Context.MODE_PRIVATE) }
    
    var apiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gemini-2.5-flash") ?: "") }
    var groqKey by remember { mutableStateOf(prefs.getString("groq_key", "") ?: "") }
    var openRouterKey by remember { mutableStateOf(prefs.getString("openrouter_key", "") ?: "") }
    
    var isRunning by remember { mutableStateOf(Bus.running) }
    var showSettings by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var chatMessages by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var inputText by remember { mutableStateOf("") }
    
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (micGranted) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                context.startActivity(intent)
            } else {
                context.startForegroundService(Intent(context, AgentService::class.java))
                isRunning = true
            }
        }
    }

    LaunchedEffect(Unit) {
        Bus.listener = { msg ->
            activity.runOnUiThread {
                if (msg.contains("429") || msg.contains("مشغول")) {
                    // تجاهل
                } else if (msg.contains("خطأ") || msg.contains("فشل") || msg.contains("نفدت") || msg.contains("فشلت")) {
                    hasError = true
                    errorMessage = msg
                    chatMessages = chatMessages + Pair("System", "⚠️ حدث خطأ، يرجى مراجعة الإعدادات.")
                } else {
                    chatMessages = chatMessages + Pair("Agent", msg)
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF121212)) {
                Spacer(Modifier.height(24.dp))
                Text("Anubis", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00B4D8), modifier = Modifier.padding(16.dp))
                Divider(color = Color.DarkGray)
                
                DrawerItem(Icons.Default.Settings, "Settings") { 
                    showSettings = true
                    scope.launch { drawerState.close() } 
                }
                
                DrawerItem(Icons.Default.Memory, "الذاكرة (معلومات)") { 
                    val mem = prefs.getString("mem", "") ?: ""
                    if (mem.isBlank()) {
                        Bus.post("الذاكرة فاضية.. لم يحفظ أنوبيس أي معلومات بعد.")
                    } else {
                        Bus.post("🧠 معلومات محفوظة:\n$mem")
                    }
                    showChat = true
                    scope.launch { drawerState.close() } 
                }

                DrawerItem(Icons.Default.History, "سجل المحادثات") { 
                    if (chatMessages.isEmpty()) {
                        Bus.post("سجل المحادثات فارغ.. ابدأ الكلام مع أنوبيس.")
                    }
                    showChat = true 
                    scope.launch { drawerState.close() } 
                }
                
                DrawerItem(Icons.Default.ModelTraining, "Models") { 
                    if (apiKey.isBlank()) {
                        Bus.post("⚠️ اكتب مفتاح Gemini أولاً في الإعدادات")
                    } else {
                        Bus.post("جاري جلب الموديلات المتاحة...")
                    }
                    scope.launch { drawerState.close() } 
                }
                
                DrawerItem(Icons.Default.Folder, "Files") { 
                    openFilesAccess(context)
                    scope.launch { drawerState.close() } 
                }
                
                DrawerItem(Icons.Default.Notifications, "Notifications") { 
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    Bus.post("فعّل Anubis في قائمة الوصول للإشعارات")
                    scope.launch { drawerState.close() } 
                }
                
                DrawerItem(Icons.Default.Explore, "Wander") { 
                    val current = prefs.getBoolean("wander", true)
                    prefs.edit().putBoolean("wander", !current).apply()
                    Bus.post("التجول: " + if (!current) "شغال ✅" else "متوقف ⏹")
                    scope.launch { drawerState.close() } 
                }
                
                DrawerItem(Icons.Default.Accessibility, "Accessibility") { 
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Bus.post("فعّل Anubis من الخدمات المثبتة ثم ارجع")
                    scope.launch { drawerState.close() } 
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Anubis", fontWeight = FontWeight.Bold, color = Color(0xFF00B4D8)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Box {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                                if (hasError) {
                                    Box(modifier = Modifier.size(10.dp).background(Color.Red, CircleShape).align(Alignment.TopEnd))
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF121212)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { 
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color.Gray else Color(0xFF00C853)),
                        shape = RoundedCornerShape(50)
                    ) { Text("Start", color = Color.White) }
                    
                    Button(
                        onClick = { context.stopService(Intent(context, AgentService::class.java)); isRunning = false },
                        colors = ButtonDefaults.buttonColors(containerColor = if (!isRunning) Color.Gray else Color(0xFFFF5252)),
                        shape = RoundedCornerShape(50)
                    ) { Text("Stop", color = Color.White) }
                }

                var isTapped by remember { mutableStateOf(false) }
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.96f,
                    targetValue = 1.02f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ), label = "scale"
                )
                val offsetY by infiniteTransition.animateFloat(
                    initialValue = -5f,
                    targetValue = 5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ), label = "offsetY"
                )

                val finalScale = if (isTapped) 0.85f else scale

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isTapped = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.robot),
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .graphicsLayer {
                                scaleX = finalScale
                                scaleY = finalScale
                                translationY = offsetY
                            },
                        contentScale = ContentScale.Fit
                    )
                }

                LaunchedEffect(isTapped) {
                    if (isTapped) {
                        delay(200)
                        isTapped = false
                    }
                }

                IconButton(onClick = { showChat = !showChat }) {
                    Icon(if (showChat) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = "Toggle Chat", tint = Color.White)
                }

                AnimatedVisibility(visible = showChat) {
                    Card(
                        modifier = Modifier.fillMaxWidth().height(250.dp).padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                    ) {
                        val listState = rememberLazyListState()
                        LaunchedEffect(chatMessages.size) {
                            if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.size - 1)
                        }
                        LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                            items(chatMessages) { (sender, msg) ->
                                ChatBubble(sender, msg)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...", color = Color.Gray) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.DarkGray,
                            focusedBorderColor = Color(0xFF00B4D8)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val msg = inputText
                                Bus.post("أنت: $msg")
                                chatMessages = chatMessages + Pair("You", msg)
                                inputText = ""
                                
                                val intent = Intent(context, AgentService::class.java).apply {
                                    action = "TEXT_INPUT"
                                    putExtra("text", msg)
                                }
                                if (Build.VERSION.SDK_INT >= 26) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                        },
                        containerColor = Color(0xFF00B4D8),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings", color = Color.White) },
            containerColor = Color(0xFF1E1E1E),
            text = {
                Column {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Gemini API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = groqKey,
                        onValueChange = { groqKey = it },
                        label = { Text("Groq API Key (للسرعة)") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = openRouterKey,
                        onValueChange = { openRouterKey = it },
                        label = { Text("OpenRouter API Key (DeepSeek)") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    
                    if (hasError) {
                        Spacer(Modifier.height(16.dp))
                        Text("⚠️ إشعار هام:", color = Color.Red, fontWeight = FontWeight.Bold)
                        Text(errorMessage, color = Color.LightGray, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { hasError = false; errorMessage = "" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("مسح الإشعار", color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().apply {
                        putString("key", apiKey)
                        putString("model", model)
                        putString("groq_key", groqKey)
                        putString("openrouter_key", openRouterKey)
                    }.apply()
                    showSettings = false
                }) { Text("Save", color = Color(0xFF00B4D8)) }
            }
        )
    }
}

@Composable
fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = text, tint = Color(0xFF00B4D8)) },
        label = { Text(text, color = Color.White) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
fun ChatBubble(sender: String, message: String) {
    val isUser = sender == "You"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) Color(0xFF00B4D8) else if (sender == "System") Color(0xFF8B0000) else Color(0xFF2C2C2C)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(12.dp),
                color = Color.White
            )
        }
    }
}

fun openFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT >= 30) {
        if (android.os.Environment.isExternalStorageManager()) return
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)))
    }
}