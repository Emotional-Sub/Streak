package com.streak.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.streak.app.data.AppRepository
import com.streak.app.data.CameraCapture
import com.streak.app.data.DashboardState
import com.streak.app.data.HabitDraft
import com.streak.app.data.HabitItem
import com.streak.app.data.LoginFormState
import com.streak.app.data.LoginSnapshot
import com.streak.app.ui.StreakTheme
import com.streak.app.util.CalendarDay
import com.streak.app.util.DashboardUtils
import com.streak.app.util.StreakUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class DashboardTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Habits("习惯", Icons.Rounded.ViewAgenda),
    Calendar("日历", Icons.Rounded.CalendarMonth),
    Stats("统计", Icons.Rounded.PieChart),
    Profile("我的", Icons.Rounded.SelfImprovement)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = AppRepository(applicationContext)
        val viewModel = ViewModelProvider(
            this,
            AppViewModel.factory(repository)
        )[AppViewModel::class.java]

        setContent {
            StreakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StreakApp(viewModel)
                }
            }
        }
    }
}

class AppViewModel(private val repository: AppRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardState())
    val uiState: StateFlow<DashboardState> = _uiState.asStateFlow()

    private var latestLogin = LoginSnapshot("", "", true, "")

    init {
        viewModelScope.launch {
            repository.observeLogin().collect { login ->
                latestLogin = login
                _uiState.value = DashboardState(
                    isLoading = false,
                    isLoggedIn = login.currentUser.isNotBlank(),
                    currentUser = login.currentUser,
                    savedLogin = LoginFormState(
                        username = login.savedUsername,
                        password = login.savedPassword,
                        rememberPassword = login.rememberPassword
                    ),
                    habits = repository.readHabits(),
                    today = StreakUtils.today()
                )
            }
        }
    }

    fun login(form: LoginFormState, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val username = form.username.trim()
            val success = repository.validateLogin(username, form.password)
            if (success) {
                repository.saveLoginState(form.copy(username = username), username)
                refreshHabits()
            }
            onResult(success)
        }
    }

    fun register(username: String, password: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(repository.registerAccount(username.trim(), password))
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }

    fun upsertHabit(existingId: Long?, draft: HabitDraft, onSaved: (HabitItem) -> Unit = {}) {
        viewModelScope.launch {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val habits = repository.readHabits().toMutableList()
            val index = habits.indexOfFirst { it.id == existingId }
            val oldItem = habits.getOrNull(index)
            val updated = HabitItem(
                id = existingId ?: System.currentTimeMillis(),
                title = draft.title.trim(),
                content = draft.content.trim(),
                reminderTime = draft.reminderTime,
                createdAt = oldItem?.createdAt ?: LocalDateTime.now().format(formatter),
                imageUri = draft.imageUri,
                completedDates = oldItem?.completedDates.orEmpty(),
                category = draft.category.ifBlank { "学习" },
                tags = draft.tagsInput.split(",").mapNotNull { tag ->
                    tag.trim().takeIf { it.isNotBlank() }
                }.distinct(),
                reminderEnabled = draft.reminderEnabled
            )
            if (index >= 0) {
                oldItem?.imageUri?.takeIf { it != updated.imageUri }?.let(repository::deletePhoto)
                habits[index] = updated
            } else {
                habits.add(updated)
            }
            repository.writeHabits(habits)
            repository.syncReminder(updated)
            refreshHabits()
            onSaved(updated)
        }
    }

    fun deleteHabit(item: HabitItem) {
        viewModelScope.launch {
            repository.writeHabits(repository.readHabits().filterNot { it.id == item.id })
            repository.cancelReminder(item.id)
            repository.deletePhoto(item.imageUri)
            refreshHabits()
        }
    }

    fun toggleComplete(item: HabitItem) {
        viewModelScope.launch {
            val today = StreakUtils.today()
            val habits = repository.readHabits().toMutableList()
            val index = habits.indexOfFirst { it.id == item.id }
            if (index < 0) return@launch

            val completedDates = item.completedDates.toMutableList().apply {
                if (today in this) remove(today) else add(today)
            }

            habits[index] = item.copy(completedDates = completedDates.distinct().sorted())
            repository.writeHabits(habits)
            refreshHabits()
        }
    }

    fun exportBackup(onResult: (File?) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repository.exportBackup() }.getOrNull())
        }
    }

    suspend fun copyGalleryImage(uri: Uri): String? = repository.copyGalleryImage(uri)

    suspend fun createCameraCapture(): CameraCapture = repository.createCameraCapture()

    fun persistCapturedPhoto(filePath: String): String? = repository.persistCapturedPhoto(filePath)

    fun deletePhoto(filePathOrUri: String?) = repository.deletePhoto(filePathOrUri)

    private suspend fun refreshHabits() {
        latestLogin = repository.currentLogin()
        _uiState.value = _uiState.value.copy(
            habits = repository.readHabits(),
            today = StreakUtils.today(),
            isLoggedIn = latestLogin.currentUser.isNotBlank(),
            currentUser = latestLogin.currentUser,
            savedLogin = LoginFormState(
                username = latestLogin.savedUsername,
                password = latestLogin.savedPassword,
                rememberPassword = latestLogin.rememberPassword
            )
        )
    }

    companion object {
        fun factory(repository: AppRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel(repository) as T
                }
            }
        }
    }
}

@Composable
private fun StreakApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    when {
        uiState.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载数据...")
            }
        }

        uiState.isLoggedIn -> {
            DashboardScreen(
                uiState = uiState,
                snackbarHostState = snackbarHostState,
                onLogout = viewModel::logout,
                onSaveHabit = viewModel::upsertHabit,
                onDeleteHabit = viewModel::deleteHabit,
                onToggleComplete = viewModel::toggleComplete,
                onExportBackup = viewModel::exportBackup,
                createCameraCapture = { viewModel.createCameraCapture() },
                persistCapturedPhoto = { path -> viewModel.persistCapturedPhoto(path) },
                copyGalleryImage = { uri -> viewModel.copyGalleryImage(uri) },
                deletePhoto = { path -> viewModel.deletePhoto(path) }
            )
        }

        else -> {
            LoginScreen(
                initialState = uiState.savedLogin,
                snackbarHostState = snackbarHostState,
                onLogin = viewModel::login,
                onRegister = viewModel::register
            )
        }
    }
}

@Composable
private fun LoginScreen(
    initialState: LoginFormState,
    snackbarHostState: SnackbarHostState,
    onLogin: (LoginFormState, (Boolean) -> Unit) -> Unit,
    onRegister: (String, String, (Result<Unit>) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var form by remember(initialState) { mutableStateOf(initialState) }
    var showRegister by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialState) {
        form = initialState
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF3F6FF), Color(0xFFF8F4FF), Color(0xFFFFFCF7))
                    )
                )
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "每日打卡",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "记录习惯，坚持成长",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = form.username,
                            onValueChange = { form = form.copy(username = it) },
                            label = { Text("用户名") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
                        OutlinedTextField(
                            value = form.password,
                            onValueChange = { form = form.copy(password = it) },
                            label = { Text("密码") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Password
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = form.rememberPassword,
                                onCheckedChange = { checked ->
                                    form = form.copy(rememberPassword = checked)
                                }
                            )
                            Text("记住密码")
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { showRegister = true }) {
                                Text("注册账号")
                            }
                        }
                        Button(
                            onClick = {
                                if (form.username.isBlank() || form.password.isBlank()) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("请输入用户名和密码")
                                    }
                                } else {
                                    onLogin(form) { success ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (success) "登录成功" else "用户名或密码错误"
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Text("登录")
                        }
                        Text(
                            text = "演示账号：student / 123456",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showRegister) {
        RegisterDialog(
            onDismiss = { showRegister = false },
            onRegister = { username, password ->
                onRegister(username, password) { result ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            result.fold(
                                onSuccess = {
                                    showRegister = false
                                    "注册成功，请登录"
                                },
                                onFailure = {
                                    it.message ?: "注册失败，请稍后重试"
                                }
                            )
                        )
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    uiState: DashboardState,
    snackbarHostState: SnackbarHostState,
    onLogout: () -> Unit,
    onSaveHabit: (Long?, HabitDraft, (HabitItem) -> Unit) -> Unit,
    onDeleteHabit: (HabitItem) -> Unit,
    onToggleComplete: (HabitItem) -> Unit,
    onExportBackup: ((File?) -> Unit) -> Unit,
    createCameraCapture: suspend () -> CameraCapture,
    persistCapturedPhoto: (String) -> String?,
    copyGalleryImage: suspend (Uri) -> String?,
    deletePhoto: (String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTabName by rememberSaveable { mutableStateOf(DashboardTab.Habits.name) }
    val selectedTab = DashboardTab.valueOf(selectedTabName)
    var query by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf("全部") }
    var showEditor by remember { mutableStateOf(false) }
    var editingHabitId by remember { mutableStateOf<Long?>(null) }
    var editorBaselineImage by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(HabitDraft()) }
    var deleteTarget by remember { mutableStateOf<HabitItem?>(null) }
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }

    val filteredHabits = remember(uiState.habits, query, categoryFilter) {
        DashboardUtils.filterHabits(uiState.habits, query, categoryFilter)
    }

    fun setDraftImage(newImageUri: String?) {
        val previous = draft.imageUri
        if (previous != null && previous != editorBaselineImage && previous != newImageUri) {
            deletePhoto(previous)
        }
        draft = draft.copy(imageUri = newImageUri)
    }

    fun openNewHabitEditor() {
        editingHabitId = null
        editorBaselineImage = null
        draft = HabitDraft(
            category = if (categoryFilter == "全部") "学习" else categoryFilter
        )
        showEditor = true
    }

    fun openEditHabitEditor(item: HabitItem) {
        editingHabitId = item.id
        editorBaselineImage = item.imageUri
        draft = item.toDraft()
        showEditor = true
    }

    fun closeEditor() {
        if (draft.imageUri != editorBaselineImage) {
            deletePhoto(draft.imageUri)
        }
        showEditor = false
        editingHabitId = null
        editorBaselineImage = null
        pendingCameraPath = null
        draft = HabitDraft()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copiedImage = copyGalleryImage(uri)
            if (copiedImage == null) {
                snackbarHostState.showSnackbar("图片选择失败")
            } else {
                setDraftImage(copiedImage)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val filePath = pendingCameraPath
        pendingCameraPath = null
        if (!success || filePath == null) {
            deletePhoto(filePath)
            return@rememberLauncherForActivityResult
        }
        val photoUri = persistCapturedPhoto(filePath)
        if (photoUri == null) {
            deletePhoto(filePath)
        } else {
            setDraftImage(photoUri)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val exportFile = pendingExportFile
        pendingExportFile = null

        if (exportFile == null) {
            return@rememberLauncherForActivityResult
        }

        if (uri == null) {
            exportFile.delete()
            scope.launch {
                snackbarHostState.showSnackbar("已取消导出")
            }
            return@rememberLauncherForActivityResult
        }

        val success = copyFileToUri(context, exportFile, uri)
        exportFile.delete()
        scope.launch {
            snackbarHostState.showSnackbar(
                if (success) "导出成功，请到你选择的位置查看" else "保存失败，请重试"
            )
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("每日打卡", fontWeight = FontWeight.Bold)
                        Text(
                            text = "欢迎你，${uiState.currentUser}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            onExportBackup { file ->
                                scope.launch {
                                    if (file == null) {
                                        snackbarHostState.showSnackbar("导出备份失败")
                                    } else {
                                        pendingExportFile = file
                                        exportLauncher.launch(file.name)
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "导出数据")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = "退出登录"
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                DashboardTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTabName = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == DashboardTab.Habits) {
                FloatingActionButton(onClick = ::openNewHabitEditor) {
                    Icon(Icons.Rounded.Add, contentDescription = "新增习惯")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                DashboardTab.Habits -> HabitListScreen(
                    uiState = uiState,
                    habits = filteredHabits,
                    query = query,
                    onQueryChange = { query = it },
                    categoryFilter = categoryFilter,
                    onCategoryFilterChange = { categoryFilter = it },
                    onEdit = ::openEditHabitEditor,
                    onDelete = { deleteTarget = it },
                    onToggleComplete = onToggleComplete
                )

                DashboardTab.Calendar -> CalendarScreen(
                    today = uiState.today,
                    habits = uiState.habits
                )

                DashboardTab.Stats -> StatsScreen(habits = uiState.habits)

                DashboardTab.Profile -> ProfileScreen(
                    currentUser = uiState.currentUser,
                    today = uiState.today,
                    habits = uiState.habits
                )
            }
        }
    }

    if (showEditor) {
        HabitEditorSheet(
            draft = draft,
            editing = editingHabitId != null,
            onDismiss = ::closeEditor,
            onDraftChange = { draft = it },
            onSave = {
                if (draft.title.isBlank() || draft.content.isBlank()) {
                    scope.launch {
                        snackbarHostState.showSnackbar("请先填写标题和内容")
                    }
                } else {
                    onSaveHabit(editingHabitId, draft) {
                        showEditor = false
                        editingHabitId = null
                        editorBaselineImage = null
                        pendingCameraPath = null
                        draft = HabitDraft()
                    }
                }
            },
            onPickTime = { current, onPicked ->
                showTimePicker(context, current, onPicked)
            },
            onOpenGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onOpenCamera = {
                scope.launch {
                    runCatching { createCameraCapture() }
                        .onSuccess { capture ->
                            pendingCameraPath = capture.filePath
                            cameraLauncher.launch(capture.uri)
                        }
                        .onFailure {
                            snackbarHostState.showSnackbar("无法打开相机")
                        }
                }
            },
            onRemoveImage = { setDraftImage(null) }
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteHabit(deleteTarget!!)
                        deleteTarget = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
            title = { Text("删除习惯") },
            text = { Text("确定删除“${deleteTarget!!.title}”吗？") }
        )
    }
}

@Composable
private fun HabitListScreen(
    uiState: DashboardState,
    habits: List<HabitItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    categoryFilter: String,
    onCategoryFilterChange: (String) -> Unit,
    onEdit: (HabitItem) -> Unit,
    onDelete: (HabitItem) -> Unit,
    onToggleComplete: (HabitItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { SummarySection(uiState) }
        item {
            FilterSection(
                query = query,
                onQueryChange = onQueryChange,
                categoryFilter = categoryFilter,
                onCategoryFilterChange = onCategoryFilterChange
            )
        }
        if (habits.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "还没有匹配的习惯",
                    subtitle = "点击右下角按钮新增习惯，或者调整搜索与分类筛选。"
                )
            }
        } else {
            items(habits, key = { it.id }) { item ->
                HabitCard(
                    item = item,
                    isCompletedToday = uiState.today in item.completedDates,
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onToggleComplete = { onToggleComplete(item) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarScreen(
    today: String,
    habits: List<HabitItem>
) {
    val completedSet = remember(habits) {
        habits.flatMap { it.completedDates }.toSet()
    }
    val monthGrid = remember(today, completedSet) {
        DashboardUtils.monthGrid(today, completedSet)
    }
    val weeks = remember(monthGrid) { monthGrid.chunked(7) }
    val todayDate = remember(today) {
        runCatching { LocalDate.parse(today) }.getOrDefault(LocalDate.now())
    }
    val streakRanking = remember(habits) {
        habits.sortedByDescending { StreakUtils.computeCurrentStreak(it.completedDates) }
    }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val selectedDateLabel = remember(selectedDate) {
        selectedDate?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }
    val selectedCompletedHabits = remember(habits, selectedDateLabel) {
        if (selectedDateLabel == null) emptyList()
        else habits.filter { selectedDateLabel in it.completedDates }
    }
    val selectedPendingHabits = remember(habits, selectedDateLabel) {
        if (selectedDateLabel == null) emptyList()
        else habits.filter { selectedDateLabel !in it.completedDates }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = todayDate.format(DateTimeFormatter.ofPattern("yyyy 年 MM 月")),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "紫色表示今天，绿色表示当天至少有一项习惯完成打卡。",
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    weeks.forEach { week ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            week.forEach { day ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when (day) {
                                        CalendarDay.Empty -> Spacer(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                        )

                                        is CalendarDay.Day -> CalendarDayCell(
                                            day = day,
                                            onClick = {
                                                selectedDate = todayDate.withDayOfMonth(day.day)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (streakRanking.isEmpty()) {
            EmptyStateCard(
                title = "暂无日历数据",
                subtitle = "先添加一个习惯并完成打卡，这里就会出现记录。"
            )
        } else {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "连续打卡排行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    streakRanking.take(5).forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("${index + 1}. ${item.title}", fontWeight = FontWeight.Medium)
                                Text(
                                    text = item.category,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${StreakUtils.computeCurrentStreak(item.completedDates)} 天",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (index != streakRanking.take(5).lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (selectedDate != null && selectedDateLabel != null) {
        AlertDialog(
            onDismissRequest = { selectedDate = null },
            confirmButton = {
                TextButton(onClick = { selectedDate = null }) {
                    Text("知道了")
                }
            },
            title = { Text("${selectedDateLabel} 打卡详情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("已完成 ${selectedCompletedHabits.size} 项，未完成 ${selectedPendingHabits.size} 项")
                    if (selectedCompletedHabits.isNotEmpty()) {
                        Text("已完成习惯", fontWeight = FontWeight.Bold)
                        selectedCompletedHabits.forEach { item ->
                            Text("• ${item.title}")
                        }
                    }
                    if (selectedPendingHabits.isNotEmpty()) {
                        Text("未完成习惯", fontWeight = FontWeight.Bold)
                        selectedPendingHabits.forEach { item ->
                            Text("• ${item.title}")
                        }
                    }
                    if (selectedCompletedHabits.isEmpty() && selectedPendingHabits.isEmpty()) {
                        Text("当天还没有任何习惯记录。")
                    }
                }
            }
        )
    }
}

@Composable
private fun StatsScreen(habits: List<HabitItem>) {
    val totalCheckIns = remember(habits) { DashboardUtils.totalCheckIns(habits) }
    val weeklyCheckIns = remember(habits) { DashboardUtils.weeklyCheckIns(habits) }
    val monthlyCheckIns = remember(habits) { DashboardUtils.monthlyCheckIns(habits) }
    val completionRate = remember(habits) { DashboardUtils.completionRate(habits) }
    val bestStreak = remember(habits) {
        habits.maxOfOrNull { StreakUtils.computeCurrentStreak(it.completedDates) } ?: 0
    }
    val categoryRows = remember(habits) {
        habits.groupBy { it.category }.map { (category, items) ->
            category to items.sumOf { it.completedDates.distinct().size }.toString()
        }
    }
    val reminderEnabledCount = remember(habits) { habits.count { it.reminderEnabled } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SummaryStatsSection(
            totalHabits = habits.size,
            totalCheckIns = totalCheckIns,
            bestStreak = bestStreak,
            completionRate = completionRate
        )
        StatsCard(
            title = "打卡总览",
            rows = listOf(
                "最近 7 天打卡次数" to weeklyCheckIns.toString(),
                "本月打卡次数" to monthlyCheckIns.toString(),
                "开启提醒的习惯" to reminderEnabledCount.toString()
            )
        )
        if (categoryRows.isEmpty()) {
            EmptyStateCard(
                title = "暂无统计数据",
                subtitle = "新增习惯并完成打卡后，这里会自动汇总。"
            )
        } else {
            StatsCard(title = "分类统计", rows = categoryRows)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileScreen(
    currentUser: String,
    today: String,
    habits: List<HabitItem>
) {
    val totalCheckIns = remember(habits) { DashboardUtils.totalCheckIns(habits) }
    val bestStreak = remember(habits) {
        habits.maxOfOrNull { StreakUtils.computeCurrentStreak(it.completedDates) } ?: 0
    }
    val completedToday = remember(habits, today) {
        habits.count { today in it.completedDates }
    }
    val reminderEnabledCount = remember(habits) { habits.count { it.reminderEnabled } }
    val categoryRows = remember(habits) {
        habits.groupBy { it.category }.map { (category, items) ->
            category to items.size.toString()
        }
    }
    val topHabit = remember(habits) {
        habits.maxByOrNull { StreakUtils.computeCurrentStreak(it.completedDates) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentUser.take(1).uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = currentUser,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "坚持不是一次爆发，而是很多次按时完成。",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryChip(label = "管理习惯", value = habits.size.toString())
                    SummaryChip(label = "累计打卡", value = totalCheckIns.toString())
                    SummaryChip(label = "今日完成", value = completedToday.toString())
                    SummaryChip(label = "最长连续", value = "${bestStreak}天")
                }
            }
        }

        StatsCard(
            title = "账号信息",
            rows = listOf(
                "当前用户" to currentUser,
                "今日日期" to today,
                "提醒已开启" to "${reminderEnabledCount} 项"
            )
        )

        if (topHabit != null) {
            StatsCard(
                title = "当前最佳习惯",
                rows = listOf(
                    "习惯名称" to topHabit.title,
                    "所属分类" to topHabit.category,
                    "连续打卡" to "${StreakUtils.computeCurrentStreak(topHabit.completedDates)} 天"
                )
            )
        } else {
            EmptyStateCard(
                title = "还没有个人数据",
                subtitle = "先新增一个习惯并完成打卡，这里会展示你的坚持成果。"
            )
        }

        if (categoryRows.isNotEmpty()) {
            StatsCard(
                title = "习惯分类分布",
                rows = categoryRows
            )
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("1. 在“习惯”页新增、编辑和删除每日打卡项目。")
                Text("2. 点击卡片右上角圆形按钮可切换今日打卡状态。")
                Text("3. 在编辑页可以选择分类、设置提醒、拍照或从相册选图。")
                Text("4. 右上角导出按钮可自行选择保存路径，生成备份文件。")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    query: String,
    onQueryChange: (String) -> Unit,
    categoryFilter: String,
    onCategoryFilterChange: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索习惯") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DashboardUtils.categories().forEach { category ->
                    AssistChip(
                        onClick = { onCategoryFilterChange(category) },
                        label = { Text(category) },
                        leadingIcon = if (category == categoryFilter) {
                            {
                                Icon(Icons.Rounded.FilterList, contentDescription = null)
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarySection(uiState: DashboardState) {
    val completedToday = uiState.habits.count { uiState.today in it.completedDates }
    val bestStreak = uiState.habits.maxOfOrNull {
        StreakUtils.computeCurrentStreak(it.completedDates)
    } ?: 0

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "今日概览",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "把想坚持的事放进这里，每天轻轻点一下，进度就会被认真记住。",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryChip(label = "习惯总数", value = uiState.habits.size.toString())
                SummaryChip(label = "今日已打卡", value = completedToday.toString())
                SummaryChip(label = "最长连续", value = "${bestStreak}天")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryStatsSection(
    totalHabits: Int,
    totalCheckIns: Int,
    bestStreak: Int,
    completionRate: Int
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "统计总览",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "这里可以快速看到整体坚持情况和各类习惯的完成表现。",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryChip(label = "习惯总数", value = totalHabits.toString())
                SummaryChip(label = "累计打卡", value = totalCheckIns.toString())
                SummaryChip(label = "最长连续", value = "${bestStreak}天")
                SummaryChip(label = "今日完成率", value = "$completionRate%")
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitCard(
    item: HabitItem,
    isCompletedToday: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleComplete: () -> Unit
) {
    val streak = StreakUtils.computeCurrentStreak(item.completedDates)
    val totalCheckIns = item.completedDates.distinct().size

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isCompletedToday) Color(0xFF3BB273) else Color(0xFFB8BFCF))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "创建时间：${item.createdAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(
                    onClick = onToggleComplete,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isCompletedToday) Color(0xFF3BB273) else Color(0xFF6D5EF7),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (isCompletedToday) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = "切换打卡状态"
                    )
                }
            }
            if (item.imageUri != null) {
                AsyncImage(
                    model = item.imageUri,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Text(item.content, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(item.category) },
                    leadingIcon = { Icon(Icons.Rounded.Timeline, contentDescription = null) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("提醒 ${item.reminderTime}") },
                    leadingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("已打卡 ${totalCheckIns} 次") }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("连续 ${streak} 天") }
                )
                if (item.reminderEnabled) {
                    AssistChip(
                        onClick = {},
                        label = { Text("提醒已开启") },
                        leadingIcon = { Icon(Icons.Rounded.Notifications, contentDescription = null) }
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (isCompletedToday) "今日已完成" else "今日未完成") }
                )
            }
            if (item.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item.tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text("#$tag") })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: CalendarDay.Day,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                when {
                    day.isToday -> Color(0xFF6D5EF7)
                    day.isCompleted -> Color(0xFFDCF6E8)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = day.day.toString(),
            color = when {
                day.isToday -> Color.White
                day.isCompleted -> Color(0xFF1A7F4B)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (day.isToday || day.isCompleted) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun StatsCard(
    title: String,
    rows: List<Pair<String, String>>
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            rows.forEachIndexed { index, (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontWeight = FontWeight.Bold)
                }
                if (index != rows.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    subtitle: String
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitEditorSheet(
    draft: HabitDraft,
    editing: Boolean,
    onDismiss: () -> Unit,
    onDraftChange: (HabitDraft) -> Unit,
    onSave: () -> Unit,
    onPickTime: (String, (String) -> Unit) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit,
    onRemoveImage: () -> Unit
) {
    var categoryExpanded by remember { mutableStateOf(false) }
    val categories = remember {
        DashboardUtils.categories().filterNot { it == "全部" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (editing) "编辑习惯" else "新增习惯",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onDraftChange(draft.copy(title = it)) },
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.content,
                onValueChange = { onDraftChange(draft.copy(content = it)) },
                label = { Text("内容") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft.category,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("分类") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { categoryExpanded = !categoryExpanded }) {
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择分类")
                        }
                    },
                    singleLine = true
                )
                DropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category) },
                            onClick = {
                                onDraftChange(draft.copy(category = category))
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft.tagsInput,
                onValueChange = { onDraftChange(draft.copy(tagsInput = it)) },
                label = { Text("标签（多个标签用逗号分隔）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.reminderTime,
                onValueChange = {},
                label = { Text("提醒时间") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            onPickTime(draft.reminderTime) { picked ->
                                onDraftChange(draft.copy(reminderTime = picked))
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = "选择提醒时间")
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Notifications, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开启提醒", modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.reminderEnabled,
                    onCheckedChange = { onDraftChange(draft.copy(reminderEnabled = it)) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenCamera, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("拍照")
                }
                OutlinedButton(onClick = onOpenGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Image, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("相册")
                }
            }
            AnimatedVisibility(visible = draft.imageUri != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = draft.imageUri,
                        contentDescription = "习惯图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                    TextButton(onClick = onRemoveImage) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("移除图片")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSave,
                    enabled = draft.title.isNotBlank() && draft.content.isNotBlank()
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun RegisterDialog(
    onDismiss: () -> Unit,
    onRegister: (String, String) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onRegister(username, password) },
                enabled = username.isNotBlank() &&
                    password.isNotBlank() &&
                    password == confirmPassword
            ) {
                Text("注册")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        title = { Text("注册账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("确认密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
        }
    )
}

private fun HabitItem.toDraft(): HabitDraft {
    return HabitDraft(
        title = title,
        content = content,
        reminderTime = reminderTime,
        imageUri = imageUri,
        category = category,
        tagsInput = tags.joinToString(", "),
        reminderEnabled = reminderEnabled
    )
}

private fun showTimePicker(
    context: Context,
    currentValue: String,
    onPicked: (String) -> Unit
) {
    val currentHour = currentValue.substringBefore(":").toIntOrNull() ?: 20
    val currentMinute = currentValue.substringAfter(":", "00").toIntOrNull() ?: 0
    TimePickerDialog(
        context,
        { _, hour, minute ->
            onPicked(String.format("%02d:%02d", hour, minute))
        },
        currentHour,
        currentMinute,
        true
    ).show()
}

private fun copyFileToUri(context: Context, file: File, uri: Uri): Boolean {
    val outputStream = context.contentResolver.openOutputStream(uri) ?: return false
    return runCatching {
        outputStream.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }.isSuccess
}
