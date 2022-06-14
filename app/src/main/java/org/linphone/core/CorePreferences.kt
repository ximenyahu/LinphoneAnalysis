/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStoreException
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log

/**
 * 是一个存储APP属性设置的类，相当于SharedPreferences的加密存储。
 * 加密使用的是 androidx.security:security-crypto-ktx 这个lib库
 */
class CorePreferences constructor(private val context: Context) {
    private var _config: Config? = null
    var config: Config
        get() = _config ?: coreContext.core.config
        set(value) {
            _config = value
        }

    /* VFS encryption */

    companion object {
        const val OVERLAY_CLICK_SENSITIVITY = 10

        private const val encryptedSharedPreferencesFile = "encrypted.pref"
    }

    val encryptedSharedPreferences: SharedPreferences? by lazy {
        val masterKey: MasterKey = MasterKey.Builder(
            context,
            MasterKey.DEFAULT_MASTER_KEY_ALIAS
        ).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        try {
            // 这里开始创建加密
            EncryptedSharedPreferences.create(
                context, encryptedSharedPreferencesFile, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (kse: KeyStoreException) {
            Log.e("[VFS] Keystore exception: $kse")
            null
        }
    }

    /**
     * VFS属性怀疑是涉及加密相关的属性。
     * VFS开启会关闭linphone logs的输出
     */
    var vfsEnabled: Boolean
        get() = encryptedSharedPreferences?.getBoolean("vfs_enabled", false) ?: false
        set(value) {
            val preferences = encryptedSharedPreferences
            if (preferences == null) {
                Log.e("[VFS] Failed to get encrypted SharedPreferences")
                return
            }

            if (!value && preferences.getBoolean("vfs_enabled", false)) {
                Log.w("[VFS] It is not possible to disable VFS once it has been enabled")
                return
            }

            preferences.edit().putBoolean("vfs_enabled", value)?.apply()
            // When VFS is enabled we disable logcat output for linphone logs
            // TODO: decide if we do it
            // logcatLogsOutput = false
        }

    /**
     * 聊天室静音：主要是通知方面
     * 这里可以指定聊天室的id
     */
    fun chatRoomMuted(id: String): Boolean {
        val sharedPreferences: SharedPreferences = coreContext.context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean(id, false)
    }

    /**
     * 聊天室静音：主要是通知方面
     */
    fun muteChatRoom(id: String, mute: Boolean) {
        val sharedPreferences: SharedPreferences = coreContext.context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean(id, mute)
        editor.apply()
    }

    /* App settings */

    /**
     * 默认为true
     * debugLogs开启，会使loggingService的log水平为LogLevel.Message
     * 以下代码在LinphoneApplication类中发现
     * Factory.instance().loggingService.setLogLevel(LogLevel.Message)
     * debugLogs开启，会在设置-高级设置中显示“发送日志”、“重启日志” 两个选项。
     */
    var debugLogs: Boolean
        get() = config.getBool("app", "debug", org.linphone.BuildConfig.DEBUG)
        set(value) {
            config.setBool("app", "debug", value)
        }

    /**
     * 和Linphone相关内容的log信息是否输出
     * 和应用SharedPreferences加密有关系 加密开启，则不输出log
     */
    var logcatLogsOutput: Boolean
        get() = config.getBool("app", "print_logs_into_logcat", true)
        set(value) {
            config.setBool("app", "print_logs_into_logcat", value)
        }

    /**
     * 设置-高级设置 在启动时开始
     */
    var autoStart: Boolean
        get() = config.getBool("app", "auto_start", true)
        set(value) {
            config.setBool("app", "auto_start", value)
        }

    /**
     * 设置-高级设置 后台模式(显示通知以使应用程序保持活动状态)
     */
    var keepServiceAlive: Boolean
        get() = config.getBool("app", "keep_service_alive", false)
        set(value) {
            config.setBool("app", "keep_service_alive", value)
        }

    /**
     * 已读且同意相关条款和策略
     * 助手-我接受Belledonne Communications的使用条款和隐私策略
     */
    var readAndAgreeTermsAndPrivacy: Boolean
        get() = config.getBool("app", "read_and_agree_terms_and_privacy", false)
        set(value) {
            config.setBool("app", "read_and_agree_terms_and_privacy", value)
        }

    /* UI */

    /**
     * 强制竖屏，默认为false
     */
    var forcePortrait: Boolean
        get() = config.getBool("app", "force_portrait_orientation", false)
        set(value) {
            config.setBool("app", "force_portrait_orientation", value)
        }

    /**
     * 替换SIP号码为用户名，默认为false
     */
    var replaceSipUriByUsername: Boolean
        get() = config.getBool("app", "replace_sip_uri_by_username", false)
        set(value) {
            config.setBool("app", "replace_sip_uri_by_username", value)
        }

    /**
     * 开启动画 默认：关闭
     * 涉及到下面导航键、通话页面的更多页面(...)
     */
    var enableAnimations: Boolean
        get() = config.getBool("app", "enable_animations", false)
        set(value) {
            config.setBool("app", "enable_animations", value)
        }

    /**
     * 暗黑模式 默认：自动
     * 设置-高级设置 暗黑模式
     */
    /** -1 means auto, 0 no, 1 yes */
    var darkMode: Int
        get() {
            if (!darkModeAllowed) return 0
            return config.getInt("app", "dark_mode", -1)
        }
        set(value) {
            config.setInt("app", "dark_mode", value)
        }

    /** Allow to make screenshots of encrypted chat rooms, disables fragment's secure mode */
    /**
     * 关闭安全模式 默认false
     * 设置-高级设置-禁用UI的安全模式(允许对敏感视图进行屏幕截屏/录制)
     */
    var disableSecureMode: Boolean
        get() = config.getBool("app", "disable_fragment_secure_mode", false)
        set(value) {
            config.setBool("app", "disable_fragment_secure_mode", value)
        }

    /* Audio */

    /* Video */

    /**
     * 视频预览 默认不开启
     * 只有平板设备有 设置-视频-在拨号器上显示摄像头预览(在启用视频下面)
     */
    var videoPreview: Boolean
        get() = config.getBool("app", "video_preview", false)
        set(value) = config.setBool("app", "video_preview", value)

    /* Chat */

    /**
     * 一条信息最多包含一个文件 默认false(这里怀疑是阻止)
     * 目前没有地方设置
     */
    var preventMoreThanOneFilePerMessage: Boolean
        get() = config.getBool("app", "prevent_more_than_one_file_per_message", false)
        set(value) {
            config.setBool("app", "prevent_more_than_one_file_per_message", value)
        }

    /**
     * 撤消通知后标记为已读 默认false
     * 设置-聊天 撤消通知后标记为已读
     * "[Notifications Manager] Chat room will be marked as read when notification will be dismissed")
     */
    var markAsReadUponChatMessageNotificationDismissal: Boolean
        get() = config.getBool("app", "mark_as_read_notif_dismissal", false)
        set(value) {
            config.setBool("app", "mark_as_read_notif_dismissal", value)
        }

    /**
     * 公开下载的媒体 默认true
     * 设置-聊天 公开下载的媒体
     */
    var makePublicMediaFilesDownloaded: Boolean
        // Keep old name for backward compatibility
        get() = config.getBool("app", "make_downloaded_images_public_in_gallery", true)
        set(value) {
            config.setBool("app", "make_downloaded_images_public_in_gallery", value)
        }

    /**
     * 始终在此应用内打开文件 默认false
     * 设置-聊天-始终在此应用内打开文件
     */
    var useInAppFileViewerForNonEncryptedFiles: Boolean
        get() = config.getBool("app", "use_in_app_file_viewer_for_non_encrypted_files", false)
        set(value) {
            config.setBool("app", "use_in_app_file_viewer_for_non_encrypted_files", value)
        }

    /**
     * 在通知中隐藏短信内容 默认false
     * 设置-聊天 在通知中隐藏短信内容
     */
    var hideChatMessageContentInNotification: Boolean
        get() = config.getBool("app", "hide_chat_message_content_in_notification", false)
        set(value) {
            config.setBool("app", "hide_chat_message_content_in_notification", value)
        }

    /**
     * 隐藏空的聊天室 默认true
     * 设置-聊天-隐藏空的聊天室
     */
    var hideEmptyRooms: Boolean
        get() = config.getBool("misc", "hide_empty_chat_rooms", true)
        set(value) {
            config.setBool("misc", "hide_empty_chat_rooms", value)
        }

    /**
     * 从已删除的代理配置中隐藏聊天室 (目前不知道是什么作用) 默认true
     * 设置-聊天-从已删除的代理配置中隐藏聊天室
     */
    var hideRoomsFromRemovedProxies: Boolean
        get() = config.getBool("misc", "hide_chat_rooms_from_removed_proxies", true)
        set(value) {
            config.setBool("misc", "hide_chat_rooms_from_removed_proxies", value)
        }

    /**
     * 设备名称 当前防爆手机名称为：SW96
     * 设置-高级设置-设备名称(动画下面)
     */
    var deviceName: String
        get() = config.getString("app", "device_name", Compatibility.getDeviceName(context))!!
        set(value) = config.setString("app", "device_name", value)

    /**
     * 在启动器中创建至聊天室的快捷方式 默认true
     * 设置-聊天-在启动器中创建至聊天室的快捷方式(visibility = gone)
     */
    var chatRoomShortcuts: Boolean
        get() = config.getBool("app", "chat_room_shortcuts", true)
        set(value) {
            config.setBool("app", "chat_room_shortcuts", value)
        }

    /* Voice Recordings */

    /**
     * 发送语音的时候最大录制时长是10分钟
     */
    var voiceRecordingMaxDuration: Int
        get() = config.getInt("app", "voice_recording_max_duration", 600000) // in ms
        set(value) = config.setInt("app", "voice_recording_max_duration", value)

    /**
     * 按下录音 默认false
     * 没有地方设置
     * 用在聊天时语音功能：按下录制 抬起停止
     */
    var holdToRecordVoiceMessage: Boolean
        get() = config.getBool("app", "voice_recording_hold_and_release_mode", false)
        set(value) = config.setBool("app", "voice_recording_hold_and_release_mode", value)

    /**
     * 录音完成后立即发送 默认false
     * 没有地方设置
     * 聊天时语音功能：按下录制 停止录制时立即发送
     */
    var sendVoiceRecordingRightAway: Boolean
        get() = config.getBool("app", "voice_recording_send_right_away", false)
        set(value) = config.setBool("app", "voice_recording_send_right_away", value)

    /* Contacts */

    /**
     * 本机联系人中的状态信息(目前了解不多) 默认false
     */
    var storePresenceInNativeContact: Boolean
        get() = config.getBool("app", "store_presence_in_native_contact", false)
        set(value) {
            config.setBool("app", "store_presence_in_native_contact", value)
        }

    /**
     * 总是问在哪个帐户中保存新创建的联系人 默认为true
     * 设置-联系人-总是问在哪个帐户中保存新创建的联系人
     */
    var showNewContactAccountDialog: Boolean
        get() = config.getBool("app", "show_new_contact_account_dialog", true)
        set(value) {
            config.setBool("app", "show_new_contact_account_dialog", value)
        }

    /**
     * 显示联络人组织 默认 contactOrganizationVisible (true)
     * 设置-联系人-显示联络人组织
     */
    var displayOrganization: Boolean
        get() = config.getBool("app", "display_contact_organization", contactOrganizationVisible)
        set(value) {
            config.setBool("app", "display_contact_organization", value)
        }

    /**
     * 在启动器中创建联系人的快捷方式 默认false
     * 设置-联系人-在启动器中创建联系人的快捷方式 visibility=gone
     */
    var contactsShortcuts: Boolean
        get() = config.getBool("app", "contact_shortcuts", false)
        set(value) {
            config.setBool("app", "contact_shortcuts", value)
        }

    /* Call */

    /**
     * 在通话之前建立媒体流
     */
    var sendEarlyMedia: Boolean
        get() = config.getBool("sip", "outgoing_calls_early_media", false)
        set(value) {
            config.setBool("sip", "outgoing_calls_early_media", value)
        }

    /**
     * 在通话之前建立媒体流 默认false
     * 设置-通话-在通话之前建立媒体流
     */
    var acceptEarlyMedia: Boolean
        get() = config.getBool("sip", "incoming_calls_early_media", false)
        set(value) {
            config.setBool("sip", "incoming_calls_early_media", value)
        }

    var autoAnswerEnabled: Boolean
        get() = config.getBool("app", "auto_answer", false)
        set(value) {
            config.setBool("app", "auto_answer", value)
        }

    var autoAnswerDelay: Int
        get() = config.getInt("app", "auto_answer_delay", 0)
        set(value) {
            config.setInt("app", "auto_answer_delay", value)
        }

    // Show overlay inside of application
    var showCallOverlay: Boolean
        get() = config.getBool("app", "call_overlay", true)
        set(value) {
            config.setBool("app", "call_overlay", value)
        }

    // Show overlay even when app is in background, requires permission
    var systemWideCallOverlay: Boolean
        get() = config.getBool("app", "system_wide_call_overlay", false)
        set(value) {
            config.setBool("app", "system_wide_call_overlay", value)
        }

    var callRightAway: Boolean
        get() = config.getBool("app", "call_right_away", false)
        set(value) {
            config.setBool("app", "call_right_away", value)
        }

    var automaticallyStartCallRecording: Boolean
        get() = config.getBool("app", "auto_start_call_record", false)
        set(value) {
            config.setBool("app", "auto_start_call_record", value)
        }

    var useTelecomManager: Boolean
        // Some permissions are required, so keep it to false so user has to manually enable it and give permissions
        get() = config.getBool("app", "use_self_managed_telecom_manager", false)
        set(value) {
            config.setBool("app", "use_self_managed_telecom_manager", value)
            // We need to disable audio focus requests when enabling telecom manager, otherwise it creates conflicts
            config.setBool("audio", "android_disable_audio_focus_requests", value)
        }

    // We will try to auto enable Telecom Manager feature, but in case user disables it don't try again
    var manuallyDisabledTelecomManager: Boolean
        get() = config.getBool("app", "user_disabled_self_managed_telecom_manager", false)
        set(value) {
            config.setBool("app", "user_disabled_self_managed_telecom_manager", value)
        }

    var routeAudioToBluetoothIfAvailable: Boolean
        get() = config.getBool("app", "route_audio_to_bluetooth_if_available", true)
        set(value) {
            config.setBool("app", "route_audio_to_bluetooth_if_available", value)
        }

    // This won't be done if bluetooth or wired headset is used
    var routeAudioToSpeakerWhenVideoIsEnabled: Boolean
        get() = config.getBool("app", "route_audio_to_speaker_when_video_enabled", true)
        set(value) {
            config.setBool("app", "route_audio_to_speaker_when_video_enabled", value)
        }

    // Automatically handled by SDK
    var pauseCallsWhenAudioFocusIsLost: Boolean
        get() = config.getBool("audio", "android_pause_calls_when_audio_focus_lost", true)
        set(value) {
            config.setBool("audio", "android_pause_calls_when_audio_focus_lost", value)
        }

    var enableFullScreenWhenJoiningVideoConference: Boolean
        get() = config.getBool("app", "enter_video_conference_enable_full_screen_mode", true)
        set(value) {
            config.setBool("app", "enter_video_conference_enable_full_screen_mode", value)
        }

    /* Assistant */

    var firstStart: Boolean
        get() = config.getBool("app", "first_start", true)
        set(value) {
            config.setBool("app", "first_start", value)
        }

    var xmlRpcServerUrl: String?
        get() = config.getString("assistant", "xmlrpc_url", null)
        set(value) {
            config.setString("assistant", "xmlrpc_url", value)
        }

    /* Dialog related */

    var limeSecurityPopupEnabled: Boolean
        get() = config.getBool("app", "lime_security_popup_enabled", true)
        set(value) {
            config.setBool("app", "lime_security_popup_enabled", value)
        }

    /* Other */

    var voiceMailUri: String?
        get() = config.getString("app", "voice_mail", null)
        set(value) {
            config.setString("app", "voice_mail", value)
        }

    var redirectDeclinedCallToVoiceMail: Boolean
        get() = config.getBool("app", "redirect_declined_call_to_voice_mail", true)
        set(value) {
            config.setBool("app", "redirect_declined_call_to_voice_mail", value)
        }

    var lastUpdateAvailableCheckTimestamp: Int
        get() = config.getInt("app", "version_check_url_last_timestamp", 0)
        set(value) {
            config.setInt("app", "version_check_url_last_timestamp", value)
        }

    var defaultAccountAvatarPath: String?
        get() = config.getString("app", "default_avatar_path", null)
        set(value) {
            config.setString("app", "default_avatar_path", value)
        }

    /* *** Read only application settings, some were previously in non_localizable_custom *** */

    /* UI related */

    val contactOrganizationVisible: Boolean
        get() = config.getBool("app", "display_contact_organization", true)

    private val darkModeAllowed: Boolean
        get() = config.getBool("app", "dark_mode_allowed", true)

    /* Feature related */

    val showScreenshotButton: Boolean
        get() = config.getBool("app", "show_take_screenshot_button_in_call", false)

    val dtmfKeypadVibration: Boolean
        get() = config.getBool("app", "dtmf_keypad_vibraton", false)

    val allowMultipleFilesAndTextInSameMessage: Boolean
        get() = config.getBool("app", "allow_multiple_files_and_text_in_same_message", true)

    val fetchContactsFromDefaultDirectory: Boolean
        get() = config.getBool("app", "fetch_contacts_from_default_directory", true)

    val delayBeforeShowingContactsSearchSpinner: Int
        get() = config.getInt("app", "delay_before_showing_contacts_search_spinner", 200)

    // From Android Contact APIs we can also retrieve the internationalized phone number
    // By default we display the same value as the native address book app
    val preferNormalizedPhoneNumbersFromAddressBook: Boolean
        get() = config.getBool("app", "prefer_normalized_phone_numbers_from_address_book", false)

    val hideStaticImageCamera: Boolean
        get() = config.getBool("app", "hide_static_image_camera", true)

    // Will disable chat feature completely
    val disableChat: Boolean
        get() = config.getBool("app", "disable_chat_feature", false)

    // This will prevent UI from showing up, except for the launcher & the foreground service notification
    val preventInterfaceFromShowingUp: Boolean
        get() = config.getBool("app", "keep_app_invisible", false)

    // By default we will record voice messages using MKV format and Opus audio encoding
    // If disabled, WAV format will be used instead. Warning: files will be heavier!
    val voiceMessagesFormatMkv: Boolean
        get() = config.getBool("app", "record_voice_messages_in_mkv_format", true)

    val useEphemeralPerDeviceMode: Boolean
        get() = config.getBool("app", "ephemeral_chat_messages_settings_per_device", true)

    // If enabled user will see all ringtones bundled in our SDK
    // and will be able to choose which one to use if not using it's device's default
    val showAllRingtones: Boolean
        get() = config.getBool("app", "show_all_available_ringtones", false)

    /* Default values related */

    val echoCancellerCalibration: Int
        get() = config.getInt("sound", "ec_delay", -1)

    val defaultDomain: String
        get() = config.getString("app", "default_domain", "sip.linphone.org")!!

    val defaultRlsUri: String
        get() = config.getString("sip", "rls_uri", "sips:rls@sip.linphone.org")!!

    val defaultLimeServerUrl: String
        get() = config.getString("lime", "lime_server_url", "https://lime.linphone.org/lime-server/lime-server.php")!!

    val debugPopupCode: String
        get() = config.getString("app", "debug_popup_magic", "#1234#")!!

    // If there is more participants than this value in a conference, force ActiveSpeaker layout
    val maxConferenceParticipantsForMosaicLayout: Int
        get() = config.getInt("app", "conference_mosaic_layout_max_participants", 6)

    val conferenceServerUri: String
        get() = config.getString(
            "app",
            "default_conference_factory_uri",
            "sip:conference-factory@sip.linphone.org"
        )!!

    val audioVideoConferenceServerUri: String
        get() = config.getString(
            "app",
            "default_audio_video_conference_factory_uri",
            "sip:videoconference-factory2@sip.linphone.org"
        )!!

    val checkUpdateAvailableInterval: Int
        get() = config.getInt("app", "version_check_interval", 86400000)

    /* Assistant */

    val showCreateAccount: Boolean
        get() = config.getBool("app", "assistant_create_account", true)

    val showLinphoneLogin: Boolean
        get() = config.getBool("app", "assistant_linphone_login", true)

    val showGenericLogin: Boolean
        get() = config.getBool("app", "assistant_generic_login", true)

    val showRemoteProvisioning: Boolean
        get() = config.getBool("app", "assistant_remote_provisioning", true)

    /* Side Menu */

    val showAccountsInSideMenu: Boolean
        get() = config.getBool("app", "side_menu_accounts", true)

    val showAssistantInSideMenu: Boolean
        get() = config.getBool("app", "side_menu_assistant", true)

    val showSettingsInSideMenu: Boolean
        get() = config.getBool("app", "side_menu_settings", true)

    val showRecordingsInSideMenu: Boolean
        get() = config.getBool("app", "side_menu_recordings", true)

    val showScheduledConferencesInSideMenu: Boolean
        get() = config.getBool("app", "side_menu_conferences", true)

    val showAboutInSideMenu: Boolean
        get() = config.getBool("app", "side_menu_about", true)

    val showQuitInSideMenu: Boolean
        get() = config.getBool("app", "side_menu_quit", true)

    /* Settings */

    val allowDtlsTransport: Boolean
        get() = config.getBool("app", "allow_dtls_transport", false)

    val showAccountSettings: Boolean
        get() = config.getBool("app", "settings_accounts", true)

    val showTunnelSettings: Boolean
        get() = config.getBool("app", "settings_tunnel", true)

    val showAudioSettings: Boolean
        get() = config.getBool("app", "settings_audio", true)

    val showVideoSettings: Boolean
        get() = config.getBool("app", "settings_video", true)

    val showCallSettings: Boolean
        get() = config.getBool("app", "settings_call", true)

    val showChatSettings: Boolean
        get() = config.getBool("app", "settings_chat", true)

    val showNetworkSettings: Boolean
        get() = config.getBool("app", "settings_network", true)

    val showContactsSettings: Boolean
        get() = config.getBool("app", "settings_contacts", true)

    val showAdvancedSettings: Boolean
        get() = config.getBool("app", "settings_advanced", true)

    val showConferencesSettings: Boolean
        get() = config.getBool("app", "settings_conferences", true)

    /* Assets stuff */

    val configPath: String
        get() = context.filesDir.absolutePath + "/.linphonerc"

    val factoryConfigPath: String
        get() = context.filesDir.absolutePath + "/linphonerc"

    val linphoneDefaultValuesPath: String
        get() = context.filesDir.absolutePath + "/assistant_linphone_default_values"

    val defaultValuesPath: String
        get() = context.filesDir.absolutePath + "/assistant_default_values"

    val ringtonesPath: String
        get() = context.filesDir.absolutePath + "/share/sounds/linphone/rings/"

    val defaultRingtonePath: String
        get() = ringtonesPath + "notes_of_the_optimistic.mkv"

    val userCertificatesPath: String
        get() = context.filesDir.absolutePath + "/user-certs"

    val staticPicturePath: String
        get() = context.filesDir.absolutePath + "/share/images/nowebcamcif.jpg"

    fun copyAssetsFromPackage() {
        copy("linphonerc_default", configPath)
        copy("linphonerc_factory", factoryConfigPath, true)
        copy("assistant_linphone_default_values", linphoneDefaultValuesPath, true)
        copy("assistant_default_values", defaultValuesPath, true)

        move(context.filesDir.absolutePath + "/linphone-log-history.db", context.filesDir.absolutePath + "/call-history.db")
        move(context.filesDir.absolutePath + "/zrtp_secrets", context.filesDir.absolutePath + "/zrtp-secrets.db")
    }

    fun getString(resource: Int): String {
        return context.getString(resource)
    }

    private fun copy(from: String, to: String, overrideIfExists: Boolean = false) {
        val outFile = File(to)
        if (outFile.exists()) {
            if (!overrideIfExists) {
                android.util.Log.i(context.getString(org.linphone.R.string.app_name), "[Preferences] File $to already exists")
                return
            }
        }
        android.util.Log.i(context.getString(org.linphone.R.string.app_name), "[Preferences] Overriding $to by $from asset")

        val outStream = FileOutputStream(outFile)
        val inFile = context.assets.open(from)
        val buffer = ByteArray(1024)
        var length: Int = inFile.read(buffer)

        while (length > 0) {
            outStream.write(buffer, 0, length)
            length = inFile.read(buffer)
        }

        inFile.close()
        outStream.flush()
        outStream.close()
    }

    private fun move(from: String, to: String, overrideIfExists: Boolean = false) {
        val inFile = File(from)
        val outFile = File(to)
        if (inFile.exists()) {
            if (outFile.exists() && !overrideIfExists) {
                android.util.Log.w(context.getString(org.linphone.R.string.app_name), "[Preferences] Can't move [$from] to [$to], destination file already exists")
            } else {
                val inStream = FileInputStream(inFile)
                val outStream = FileOutputStream(outFile)

                val buffer = ByteArray(1024)
                var read: Int
                while (inStream.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                }

                inStream.close()
                outStream.flush()
                outStream.close()

                inFile.delete()
                android.util.Log.i(context.getString(org.linphone.R.string.app_name), "[Preferences] Successfully moved [$from] to [$to]")
            }
        } else {
            android.util.Log.w(context.getString(org.linphone.R.string.app_name), "[Preferences] Can't move [$from] to [$to], source file doesn't exists")
        }
    }
}
