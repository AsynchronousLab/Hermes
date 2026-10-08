package com.hermes.android.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.ui.chat.HermesSpeaker
import com.hermes.android.ui.contacts.ContactsScreen
import com.hermes.android.ui.contacts.ContactsViewModel
import com.hermes.android.ui.groups.RoomChatScreen
import com.hermes.android.ui.groups.RoomChatViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.android.HermesApp
import com.hermes.android.ui.chat.ChatScreen
import com.hermes.android.ui.chat.ChatViewModel
import com.hermes.android.ui.sessions.SessionListScreen
import com.hermes.android.ui.sessions.SessionListViewModel
import com.hermes.android.ui.settings.SettingsScreen
import com.hermes.android.ui.settings.SettingsViewModel
import com.hermes.android.ui.tools.ToolsScreen
import com.hermes.android.ui.tools.ToolsViewModel

private object Routes {
    const val CHATS = "chats"
    const val CONTACTS = "contacts"
    const val MORE = "more"
    const val CHAT = "chat"
    const val ROOM = "room"
    fun chat(session: String?) =
        if (session == null) CHAT else "$CHAT?session=${android.net.Uri.encode(session)}"

    /**
     * A group room. `self` is the profile whose identity `groups.send` posts
     * under, so the room can tell its own messages from the members'.
     */
    fun room(roomId: String, self: String) =
        "$ROOM?room=${android.net.Uri.encode(roomId)}&self=${android.net.Uri.encode(self)}"
}

private data class TabSpec(val route: String, val label: String, val icon: ImageVector)

// 联系人 is its own destination; 更多 absorbs the old 设置 page.
private val TABS = listOf(
    TabSpec(Routes.CHATS, "消息", Icons.AutoMirrored.Filled.Chat),
    TabSpec(Routes.CONTACTS, "联系人", Icons.Default.People),
    TabSpec(Routes.MORE, "更多", Icons.Default.GridView),
)

@Composable
fun HermesAppRoot() {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HermesApp
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Exact match on purpose: "chats" also startsWith("chat"), and treating the
    // session list as a chat screen hides the bottom bar entirely.
    val onChat = currentRoute == Routes.CHAT ||
        currentRoute?.startsWith("${Routes.CHAT}?") == true

    // A room is a full-screen conversation too — keep the tab bar out of it.
    val inRoom = currentRoute?.startsWith("${Routes.ROOM}?") == true
    val immersive = onChat || inRoom

    Scaffold(
        bottomBar = {
            if (!immersive) {
                NavigationBar {
                    TABS.forEach { spec ->
                        val selected = backStack?.destination?.hierarchy
                            ?.any { it.route == spec.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(spec.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(spec.icon, spec.label) },
                            label = { Text(spec.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHATS,
            modifier = Modifier
                .padding(pad)
                // padding() does not *consume* insets, so the inner TopAppBar
                // would apply the status-bar inset a second time and leave a
                // blank strip. Consume it here; each screen then owns exactly one.
                .consumeWindowInsets(pad)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            composable(Routes.CHATS) {
                val vm: SessionListViewModel = viewModel(
                    factory = SessionListViewModel.Factory(app.repository, app.settings)
                )
                val state by vm.state.collectAsState()
                SessionListScreen(
                    state = state,
                    onSearch = vm::search,
                    onRefresh = { vm.refresh() },
                    onOpen = { s -> vm.open(s) { route -> navController.navigate(Routes.chat(route)) } },
                    onOpenRoom = { roomId ->
                        navController.navigate(Routes.room(roomId, "default"))
                    },
                    onNewSession = {
                        vm.newSession { sid -> navController.navigate(Routes.chat(sid)) }
                    },
                    onOpenSettings = { navController.navigate(Routes.MORE) },
                    onDismiss = vm::clearBanner,
                    onDelete = vm::delete,
                )
            }

            composable(Routes.CONTACTS) {
                val vm: ContactsViewModel = viewModel(
                    factory = ContactsViewModel.Factory(app.repository, app.settings)
                )
                val state by vm.state.collectAsState()
                ContactsScreen(
                    state = state,
                    onTab = vm::selectTab,
                    onRefresh = { vm.refresh() },
                    onOpenProfile = { profile ->
                        navController.navigate("chat?session=profile:${android.net.Uri.encode(profile)}")
                    },
                    onOpenCreate = vm::openCreateSheet,
                    onOpenGroup = { g ->
                        // The gateway posts as whichever profile we name, so the
                        // room can tell our own messages from the members'.
                        // `default` is the right identity for a human posting here.
                        navController.navigate(Routes.room(g.roomId, "default"))
                    },
                    onToggleMember = vm::toggleMember,
                    onDraftName = vm::setDraftName,
                    onCreateGroup = { vm.createGroup { } },
                    onCloseSheet = vm::closeCreateSheet,
                    onClear = vm::clearMessage,
                )
            }

            composable(Routes.MORE) {
                val toolsVm: ToolsViewModel = viewModel(
                    factory = ToolsViewModel.Factory(app.repository, app.settings)
                )
                val tools by toolsVm.state.collectAsState()
                val settingsVm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(app.repository, app.settings)
                )
                val settings by settingsVm.state.collectAsState()
                var showBackend by rememberSaveable { mutableStateOf(false) }

                if (showBackend) {
                    SettingsScreen(
                        state = settings,
                        onBaseUrl = settingsVm::onBaseUrl,
                        onUsername = settingsVm::onUsername,
                        onPassword = settingsVm::onPassword,
                        onTogglePassword = settingsVm::togglePassword,
                        onSave = settingsVm::save,
                        onTest = settingsVm::testConnection,
                        onBack = { showBackend = false },
                    )
                } else {
                    ToolsScreen(
                        state = tools,
                        onTab = toolsVm::selectTab,
                        onReload = { toolsVm.load() },
                        onBrowse = toolsVm::browse,
                        onExpandToolset = toolsVm::expandToolset,
                        onToggleToolset = toolsVm::toggleToolset,
                        onOpenSkill = toolsVm::openSkill,
                        onToggleSkill = toolsVm::toggleSkill,
                        onOpenSettings = { showBackend = true },
                        onCloseSkill = toolsVm::clearSkillDetail,
                        onClear = toolsVm::clearMessage,
                    )
                }
            }

            composable(Routes.CHAT) {
                // Deep-link target with no session: the ViewModel creates a fresh one.
                val vm: ChatViewModel = viewModel(
                    factory = ChatViewModel.Factory(app.repository, app.settings, null)
                )
                ChatRoute(
                    vm = vm,
                    navController = navController,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "${Routes.CHAT}?session={session}",
                arguments = listOf(
                    navArgument("session") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
            ) { entry ->
                val session = entry.arguments?.getString("session")
                val vm: ChatViewModel = viewModel(
                    factory = ChatViewModel.Factory(app.repository, app.settings, session)
                )
                ChatRoute(vm = vm, navController = navController, onBack = { navController.popBackStack() })
            }

            composable(
                route = "${Routes.ROOM}?room={room}&self={self}",
                arguments = listOf(
                    navArgument("room") { type = NavType.StringType },
                    navArgument("self") {
                        type = NavType.StringType
                        defaultValue = "default"
                    },
                ),
            ) { entry ->
                val roomId = entry.arguments?.getString("room").orEmpty()
                val self = entry.arguments?.getString("self").orEmpty().ifBlank { "default" }
                val vm: RoomChatViewModel = viewModel(
                    key = "room-$roomId",
                    factory = RoomChatViewModel.Factory(app.repository, roomId, self),
                )
                val state by vm.state.collectAsState()
                RoomChatScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onSend = vm::send,
                    onRetry = vm::retry,
                    onHold = vm::hold,
                    onDismiss = { vm.clearError(); vm.clearNotice() },
                )
            }
        }
    }
}

@Composable
private fun ChatRoute(
    vm: ChatViewModel,
    navController: NavHostController,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showModels by remember { mutableStateOf(false) }
    var showReasoning by remember { mutableStateOf(false) }

    val speaker = remember(context) { HermesSpeaker(context) }
    DisposableEffect(context) { onDispose { speaker.shutdown() } }

    if (showModels) {
        ModelPickerSheet(
            providers = state.models,
            current = state.model,
            currentProvider = state.provider,
            onPick = { p, m ->
                vm.selectModel(p, m)
                showModels = false
            },
            onDismiss = { showModels = false },
        )
    }
    if (showReasoning) {
        ReasoningPickerSheet(
            current = state.reasoning,
            onPick = {
                vm.selectReasoning(it)
                showReasoning = false
            },
            onDismiss = { showReasoning = false },
        )
    }

    ChatScreen(
        state = state,
        onSend = vm::send,
        onPickModel = { showModels = true },
        onPickReasoning = { showReasoning = true },
        onForkFrom = { msg -> vm.forkFrom(msg) },
        onNewSession = vm::newSession,
        onDismiss = vm::clearNotice,
        onAddAttachments = vm::addAttachments,
        onRemoveAttachment = vm::removeAttachment,
        onAttachmentId = { vm.nextAttachmentId() },
        onSpeakToggle = vm::setSpeakReplies,
        onSpeak = { speaker.speak(it) },
        onRequestResponse = vm::respondToRequest,
        onReadError = vm::showError,
    )
}