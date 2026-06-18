package com.streak.app.data

import kotlinx.serialization.Serializable

@Serializable
data class HabitItem(
    val id: Long,
    val title: String,
    val content: String,
    val reminderTime: String,
    val createdAt: String,
    val imageUri: String? = null,
    val completedDates: List<String> = emptyList(),
    val category: String = "学习",
    val tags: List<String> = emptyList(),
    val reminderEnabled: Boolean = true
)

data class HabitDraft(
    val title: String = "",
    val content: String = "",
    val reminderTime: String = "20:00",
    val imageUri: String? = null,
    val category: String = "学习",
    val tagsInput: String = "",
    val reminderEnabled: Boolean = true
)

@Serializable
data class UserAccount(
    val username: String,
    val password: String
)

data class LoginFormState(
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true
)

data class DashboardState(
    val isLoading: Boolean = true,
    val isLoggedIn: Boolean = false,
    val currentUser: String = "",
    val savedLogin: LoginFormState = LoginFormState(),
    val habits: List<HabitItem> = emptyList(),
    val today: String = ""
)

@Serializable
data class HabitBackup(
    val exportedAt: String,
    val habits: List<HabitItem>
)
