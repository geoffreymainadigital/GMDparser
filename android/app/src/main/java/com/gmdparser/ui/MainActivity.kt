package com.gmdparser.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gmdparser.data.repository.TaxonomyRepository
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.screens.*
import com.gmdparser.ui.theme.GMDParserTheme
import com.gmdparser.ui.theme.ThemeManager
import com.gmdparser.util.AppPreferences
import kotlinx.coroutines.launch

enum class Screen(val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    REVIEW("Review", Icons.Default.FactCheck),
    HISTORY("History", Icons.Default.ReceiptLong),
    ACCOUNTS("Accounts", Icons.Default.AccountBalance),
    CATEGORIES("Categories", Icons.Default.Category),
    SAVINGS("Savings", Icons.Default.Savings),
    DEBTS("Debts", Icons.Default.Payment),
    GOALS("Goals", Icons.Default.TrackChanges),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise shared preferences before anything else
        AppPreferences.init(this)
        TransactionRepository.init(this)
        ThemeManager.init()

        checkAndRequestPermissions()

        val initialScreen = if (intent?.hasExtra("OPEN_REVIEW_CODE") == true) {
            Screen.REVIEW
        } else {
            Screen.DASHBOARD
        }

        setContent {
            GMDParserTheme {
                AppEntryPoint(initialScreen = initialScreen)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

/**
 * Top-level entry point: shows PIN screen if enabled, then the main app.
 */
@Composable
fun AppEntryPoint(initialScreen: Screen = Screen.DASHBOARD) {
    var unlocked by remember { mutableStateOf(!AppPreferences.isPinEnabled) }

    if (!unlocked) {
        PinLockScreen(
            mode = PinScreenMode.UNLOCK,
            onUnlocked = { unlocked = true }
        )
    } else {
        MainAppHost(initialScreen = initialScreen)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppHost(initialScreen: Screen = Screen.DASHBOARD) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    val pendingCount by TransactionRepository.pendingTransactions.collectAsState()
    val scope = rememberCoroutineScope()

    // Initialise taxonomy once
    LaunchedEffect(Unit) {
        TaxonomyRepository.refreshTaxonomy()
    }

    // Prefetch BOTH dashboard periods in parallel so the toggle is instant.
    // Each coroutine writes to its own dedicated StateFlow in the repository,
    // so they never overwrite each other's data.
    LaunchedEffect(Unit) {
        scope.launch { TransactionRepository.fetchDashboard("monthly") }
        scope.launch { TransactionRepository.fetchDashboard("annual") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "GMDParser • ${currentScreen.title}",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                val primaryTabs = listOf(Screen.DASHBOARD, Screen.REVIEW, Screen.HISTORY, Screen.ACCOUNTS, Screen.SETTINGS)
                primaryTabs.forEach { screen ->
                    NavigationBarItem(
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        icon = {
                            if (screen == Screen.REVIEW && pendingCount.isNotEmpty()) {
                                BadgedBox(badge = {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text(pendingCount.size.toString())
                                    }
                                }) {
                                    Icon(screen.icon, contentDescription = screen.title)
                                }
                            } else {
                                Icon(screen.icon, contentDescription = screen.title)
                            }
                        },
                        label = { Text(screen.title, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentScreen) {
                Screen.DASHBOARD -> DashboardScreen(
                    onNavigateToReview = { currentScreen = Screen.REVIEW },
                    onNavigateToHistory = { currentScreen = Screen.HISTORY },
                    onNavigateToAccounts = { currentScreen = Screen.ACCOUNTS },
                    onNavigateToSavings = { currentScreen = Screen.SAVINGS }
                )
                Screen.REVIEW    -> ReviewConfirmScreen()
                Screen.HISTORY   -> HistoryScreen()
                Screen.ACCOUNTS  -> AccountsScreen()
                Screen.CATEGORIES -> CategoriesScreen()
                Screen.SAVINGS   -> SavingsScreen()
                Screen.DEBTS     -> DebtsScreen()
                Screen.GOALS     -> GoalsScreen()
                Screen.SETTINGS  -> SettingsScreen()
            }
        }
    }
}
