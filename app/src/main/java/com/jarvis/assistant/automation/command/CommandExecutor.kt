package com.jarvis.assistant.automation.command

import android.util.Log
import com.jarvis.assistant.audio.VoiceControlIntent
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.storage.PendingStorageAction
import com.jarvis.assistant.storage.SafActionCoordinator
import com.jarvis.assistant.storage.SafFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager,
    private val safFileManager: SafFileManager
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: JarvisCommand): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "Executing command: $command")
        when (command) {
            // YouTube
            is JarvisCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is JarvisCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is JarvisCommand.YouTubeAiAssistantCommand -> {
                val apiKey = com.jarvis.assistant.JarvisApp.instance.youTubePreferences.youtubeApiKey
                val intentRes = com.jarvis.assistant.automation.youtube.YouTubeIntentHandler.parse(command.query)
                if (intentRes != null) {
                    val (answer, _) = com.jarvis.assistant.automation.youtube.YouTubeIntentHandler.generateNaturalAnswer(intentRes, apiKey)
                    val ctx = com.jarvis.assistant.JarvisApp.instance.applicationContext
                    val intent = android.content.Intent(ctx, com.jarvis.assistant.ui.youtube.YouTubeResultsActivity::class.java).apply {
                        putExtra(com.jarvis.assistant.ui.youtube.YouTubeResultsActivity.EXTRA_QUERY, command.query)
                        putExtra(com.jarvis.assistant.ui.youtube.YouTubeResultsActivity.EXTRA_TITLE, "YouTube AI Assistant")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        ctx.startActivity(intent)
                    } catch (_: Exception) {}
                    answer
                } else {
                    youtubeManager.searchYouTube(command.query)
                }
            }
            is JarvisCommand.PauseMedia -> youtubeManager.pausePlayback()
            is JarvisCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is JarvisCommand.NextMedia -> youtubeManager.nextMedia()
            is JarvisCommand.PreviousMedia -> youtubeManager.previousMedia()
            is JarvisCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is JarvisCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is JarvisCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is JarvisCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is JarvisCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is JarvisCommand.OpenApp -> phoneManager.openApp(command.packageName)
            is JarvisCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
            is JarvisCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
            is JarvisCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp
            is JarvisCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
            is JarvisCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
            is JarvisCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is JarvisCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is JarvisCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)

            // Memory
            is JarvisCommand.Remember -> memoryManager.rememberFact(command.text)
            is JarvisCommand.Forget -> memoryManager.forgetFact(command.query)
            is JarvisCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is JarvisCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is JarvisCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is JarvisCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is JarvisCommand.GoToBackground -> "Going to background."

            // Android SAF File & Folder Management
            is JarvisCommand.CreateFolder -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.CreateFolder(command.folderName, command.parentFolderHint)
                )
                res.message
            }
            is JarvisCommand.CreateFile -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.CreateOrWriteFile(
                        fileName = command.fileName,
                        content = command.content,
                        fileType = command.fileType,
                        folderHint = command.folderHint,
                        overwriteConfirmed = false
                    )
                )
                res.message
            }
            is JarvisCommand.ListStorageFiles -> {
                val res = safFileManager.listAndFindItems(command.query, command.folderHint)
                if (res.requiresPermission) {
                    SafActionCoordinator.requestFolderPermissionPrompt(
                        reason = res.message,
                        suggestedFolder = res.folderLabel.orEmpty(),
                        pendingAction = null
                    )
                }
                SafActionCoordinator.recordAction("List Files", res.message, res.success)
                res.message
            }
            is JarvisCommand.CheckFileExists -> {
                val res = safFileManager.checkExists(command.itemName, command.folderHint)
                if (res.requiresPermission) {
                    SafActionCoordinator.requestFolderPermissionPrompt(
                        reason = res.message,
                        suggestedFolder = res.folderLabel.orEmpty(),
                        pendingAction = null
                    )
                }
                SafActionCoordinator.recordAction("Check Exists", res.message, res.success)
                res.message
            }
            is JarvisCommand.RenameStorageItem -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.RenameItem(command.currentName, command.newName, command.folderHint)
                )
                res.message
            }
            is JarvisCommand.DeleteStorageItem -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.DeleteItem(
                        targetName = command.targetName,
                        folderHint = command.folderHint
                    )
                )
                res.message
            }
            is JarvisCommand.ConfirmStorageAction -> {
                val res = SafActionCoordinator.confirmCurrentPrompt(safFileManager, command.confirmed)
                res?.message ?: "No pending file confirmation found."
            }

            // Continuous Voice Listening Controls
            is JarvisCommand.VoiceListeningControl -> {
                when (command.intent) {
                    VoiceControlIntent.START_LISTENING -> {
                        JarvisConversationService.resumeListening()
                        "Voice listening resumed."
                    }
                    VoiceControlIntent.STOP_LISTENING -> {
                        JarvisConversationService.pauseListening()
                        "Voice listening paused."
                    }
                    VoiceControlIntent.CONTINUE_LISTENING -> {
                        JarvisConversationService.continueListening()
                        "Continuous listening active."
                    }
                    VoiceControlIntent.CANCEL -> {
                        SafActionCoordinator.dismissPrompt()
                        JarvisConversationService.cancelCurrentTurnOrTask()
                        "Cancelled."
                    }
                }
            }
        }
    }
}
