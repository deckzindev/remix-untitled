package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.security.CryptoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {
  private val prefs: SharedPreferences =
    context.getSharedPreferences("gitbuilder_prefs", Context.MODE_PRIVATE)

  // Initialize and migrate token if stored in plaintext
  private val initialToken: String = run {
    val rawStored = prefs.getString(KEY_GITHUB_TOKEN_ENCRYPTED, null)
    if (rawStored != null) {
      CryptoManager.decrypt(rawStored)
    } else {
      // Check legacy unencrypted key
      val legacy = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
      if (legacy.isNotBlank()) {
        val encrypted = CryptoManager.encrypt(legacy)
        prefs.edit()
          .putString(KEY_GITHUB_TOKEN_ENCRYPTED, encrypted)
          .remove(KEY_GITHUB_TOKEN)
          .apply()
        legacy
      } else {
        ""
      }
    }
  }

  private val _githubToken = MutableStateFlow(initialToken)
  val githubToken: StateFlow<String> = _githubToken.asStateFlow()

  private val _githubUsername = MutableStateFlow(prefs.getString(KEY_GITHUB_USERNAME, "") ?: "")
  val githubUsername: StateFlow<String> = _githubUsername.asStateFlow()

  private val _defaultRepo = MutableStateFlow(prefs.getString(KEY_DEFAULT_REPO, "") ?: "")
  val defaultRepo: StateFlow<String> = _defaultRepo.asStateFlow()

  private val _defaultVariant = MutableStateFlow(prefs.getString(KEY_DEFAULT_VARIANT, "release") ?: "release")
  val defaultVariant: StateFlow<String> = _defaultVariant.asStateFlow()

  private val _engineStrategy = MutableStateFlow(prefs.getString(KEY_ENGINE_STRATEGY, "compatibility") ?: "compatibility")
  val engineStrategy: StateFlow<String> = _engineStrategy.asStateFlow()

  fun saveGitHubToken(token: String) {
    val cleanToken = token.trim()
    val encrypted = CryptoManager.encrypt(cleanToken)
    prefs.edit()
      .putString(KEY_GITHUB_TOKEN_ENCRYPTED, encrypted)
      .remove(KEY_GITHUB_TOKEN)
      .apply()
    _githubToken.value = cleanToken
  }

  fun saveGitHubUsername(username: String) {
    prefs.edit().putString(KEY_GITHUB_USERNAME, username).apply()
    _githubUsername.value = username
  }

  fun saveDefaultRepo(repo: String) {
    prefs.edit().putString(KEY_DEFAULT_REPO, repo).apply()
    _defaultRepo.value = repo
  }

  fun saveDefaultVariant(variant: String) {
    prefs.edit().putString(KEY_DEFAULT_VARIANT, variant).apply()
    _defaultVariant.value = variant
  }

  fun saveEngineStrategy(strategy: String) {
    prefs.edit().putString(KEY_ENGINE_STRATEGY, strategy).apply()
    _engineStrategy.value = strategy
  }

  fun clearAuth() {
    prefs.edit()
      .remove(KEY_GITHUB_TOKEN_ENCRYPTED)
      .remove(KEY_GITHUB_TOKEN)
      .remove(KEY_GITHUB_USERNAME)
      .apply()
    _githubToken.value = ""
    _githubUsername.value = ""
  }

  companion object {
    private const val KEY_GITHUB_TOKEN = "key_github_token"
    private const val KEY_GITHUB_TOKEN_ENCRYPTED = "key_github_token_encrypted_v2"
    private const val KEY_GITHUB_USERNAME = "key_github_username"
    private const val KEY_DEFAULT_REPO = "key_default_repo"
    private const val KEY_DEFAULT_VARIANT = "key_default_variant"
    private const val KEY_ENGINE_STRATEGY = "key_engine_strategy"
  }
}
