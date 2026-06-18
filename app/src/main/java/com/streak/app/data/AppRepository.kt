package com.streak.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streak.app.reminder.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val Context.dataStore by preferencesDataStore(name = "streak_preferences")

class AppRepository(private val context: Context) {
    private val reminderScheduler = ReminderScheduler(context)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val habitsFile = File(context.filesDir, "habits.json")
    private val accountsFile = File(context.filesDir, "accounts.json")
    private val imageDir = File(context.filesDir, "habit_images").apply { mkdirs() }
    private val backupDir = File(context.filesDir, "exports").apply { mkdirs() }

    private object Keys {
        val savedUsername = stringPreferencesKey("saved_username")
        val savedPassword = stringPreferencesKey("saved_password")
        val rememberPassword = booleanPreferencesKey("remember_password")
        val currentUser = stringPreferencesKey("current_user")
    }

    fun observeLogin(): Flow<LoginSnapshot> {
        return context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                LoginSnapshot(
                    savedUsername = prefs[Keys.savedUsername].orEmpty(),
                    savedPassword = prefs[Keys.savedPassword].orEmpty(),
                    rememberPassword = prefs[Keys.rememberPassword] ?: true,
                    currentUser = prefs[Keys.currentUser].orEmpty()
                )
            }
    }

    suspend fun currentLogin(): LoginSnapshot = observeLogin().first()

    suspend fun saveLoginState(formState: LoginFormState, currentUser: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.rememberPassword] = formState.rememberPassword
            prefs[Keys.currentUser] = currentUser
            if (formState.rememberPassword) {
                prefs[Keys.savedUsername] = formState.username
                prefs[Keys.savedPassword] = formState.password
            } else {
                prefs[Keys.savedUsername] = ""
                prefs[Keys.savedPassword] = ""
            }
        }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs[Keys.currentUser] = ""
        }
    }

    suspend fun validateLogin(username: String, password: String): Boolean {
        return loadAccounts().any { it.username == username && it.password == password }
    }

    suspend fun registerAccount(username: String, password: String): Result<Unit> {
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("用户名和密码不能为空"))
        }

        val accounts = loadAccounts()
        if (accounts.any { it.username == username }) {
            return Result.failure(IllegalArgumentException("该用户名已存在"))
        }

        saveAccounts(accounts + UserAccount(username = username, password = password))
        return Result.success(Unit)
    }

    suspend fun readHabits(): List<HabitItem> {
        if (!habitsFile.exists()) {
            writeHabits(seedHabits())
        }
        return runCatching {
            json.decodeFromString<List<HabitItem>>(habitsFile.readText(Charsets.UTF_8))
        }.getOrElse {
            seedHabits().also { habits -> writeHabits(habits) }
        }
    }

    suspend fun writeHabits(habits: List<HabitItem>) {
        habitsFile.writeText(
            text = json.encodeToString(habits.sortedByDescending { it.createdAt }),
            charset = Charsets.UTF_8
        )
    }

    suspend fun exportBackup(): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val file = File(backupDir, "habit_backup_$timestamp.json")
        val payload = HabitBackup(
            exportedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            habits = readHabits()
        )
        file.writeText(json.encodeToString(payload), Charsets.UTF_8)
        return file
    }

    fun syncReminder(habit: HabitItem) {
        reminderScheduler.schedule(habit)
    }

    fun cancelReminder(habitId: Long) {
        reminderScheduler.cancel(habitId)
    }

    suspend fun createCameraCapture(): CameraCapture {
        val file = File(imageDir, "camera_${System.currentTimeMillis()}.jpg")
        return CameraCapture(
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            ),
            filePath = file.absolutePath
        )
    }

    fun persistCapturedPhoto(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        return Uri.fromFile(file).toString()
    }

    fun deletePhoto(filePathOrUri: String?) {
        if (filePathOrUri.isNullOrBlank()) return
        runCatching {
            val directFile = File(filePathOrUri)
            val target = when {
                directFile.exists() -> directFile
                else -> Uri.parse(filePathOrUri).path?.let(::File)
            }
            target?.takeIf { it.exists() }?.delete()
        }
    }

    fun copyGalleryImage(uri: Uri): String? {
        val extension = context.contentResolver.getType(uri)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "jpg"
        val target = File(imageDir, "gallery_${System.currentTimeMillis()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return Uri.fromFile(target).toString()
    }

    private suspend fun loadAccounts(): List<UserAccount> {
        if (!accountsFile.exists()) {
            saveAccounts(listOf(UserAccount(username = "student", password = "123456")))
        }
        return runCatching {
            json.decodeFromString<List<UserAccount>>(accountsFile.readText(Charsets.UTF_8))
        }.getOrElse {
            val defaults = listOf(UserAccount(username = "student", password = "123456"))
            saveAccounts(defaults)
            defaults
        }
    }

    private suspend fun saveAccounts(accounts: List<UserAccount>) {
        accountsFile.writeText(
            text = json.encodeToString(accounts),
            charset = Charsets.UTF_8
        )
    }

    private fun seedHabits(): List<HabitItem> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val now = LocalDateTime.now().format(formatter)
        return listOf(
            HabitItem(
                id = System.currentTimeMillis(),
                title = "晨跑打卡",
                content = "坚持晨跑 30 分钟，结束后做 5 分钟拉伸。",
                reminderTime = "06:30",
                createdAt = now,
                category = "运动",
                tags = listOf("晨练", "有氧"),
                reminderEnabled = true
            ),
            HabitItem(
                id = System.currentTimeMillis() + 1,
                title = "英语单词复习",
                content = "复习 25 个英语单词，并拍照记录学习笔记。",
                reminderTime = "21:00",
                createdAt = now,
                category = "学习",
                tags = listOf("英语", "单词"),
                reminderEnabled = true
            )
        )
    }
}

data class LoginSnapshot(
    val savedUsername: String,
    val savedPassword: String,
    val rememberPassword: Boolean,
    val currentUser: String
)

data class CameraCapture(
    val uri: Uri,
    val filePath: String
)
