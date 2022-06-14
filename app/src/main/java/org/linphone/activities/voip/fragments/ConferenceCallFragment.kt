/*
 * Copyright (c) 2010-2022 Belledonne Communications SARL.
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
package org.linphone.activities.voip.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Chronometer
import android.widget.RelativeLayout
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.navigation.navGraphViewModels
import androidx.window.layout.FoldingFeature
import com.google.android.material.snackbar.Snackbar
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.activities.GenericFragment
import org.linphone.activities.main.MainActivity
import org.linphone.activities.navigateToCallsList
import org.linphone.activities.navigateToConferenceLayout
import org.linphone.activities.navigateToConferenceParticipants
import org.linphone.activities.voip.ConferenceDisplayMode
import org.linphone.activities.voip.viewmodels.CallsViewModel
import org.linphone.activities.voip.viewmodels.ConferenceViewModel
import org.linphone.activities.voip.viewmodels.ControlsViewModel
import org.linphone.activities.voip.viewmodels.StatisticsListViewModel
import org.linphone.activities.voip.views.RoundCornersTextureView
import org.linphone.core.Conference
import org.linphone.core.StreamType
import org.linphone.core.tools.Log
import org.linphone.databinding.VoipConferenceCallFragmentBinding

class ConferenceCallFragment : GenericFragment<VoipConferenceCallFragmentBinding>() {
    private val controlsViewModel: ControlsViewModel by navGraphViewModels(R.id.call_nav_graph)
    private val callsViewModel: CallsViewModel by navGraphViewModels(R.id.call_nav_graph)
    private val conferenceViewModel: ConferenceViewModel by navGraphViewModels(R.id.call_nav_graph)
    private val statsViewModel: StatisticsListViewModel by navGraphViewModels(R.id.call_nav_graph)

    override fun getLayoutId(): Int = R.layout.voip_conference_call_fragment

    override fun onStart() {
        useMaterialSharedAxisXForwardAnimation = false

        super.onStart()
    }

    @SuppressLint("CutPasteId")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        controlsViewModel.hideCallStats() // In case it was toggled on during incoming/outgoing fragment was visible

        binding.lifecycleOwner = viewLifecycleOwner

        binding.controlsViewModel = controlsViewModel

        binding.callsViewModel = callsViewModel

        binding.conferenceViewModel = conferenceViewModel

        binding.statsViewModel = statsViewModel

        conferenceViewModel.conferenceDisplayMode.observe(
            viewLifecycleOwner
        ) { displayMode ->
            startTimer(R.id.active_conference_timer)
            if (displayMode == ConferenceDisplayMode.ACTIVE_SPEAKER) {
                if (conferenceViewModel.conferenceExists.value == true) {
                    Log.i("[Conference Call] Local participant is in conference and current layout is active speaker, updating Core's native window id")
                    val layout =
                        binding.root.findViewById<RelativeLayout>(R.id.conference_active_speaker_layout)
                    val window =
                        layout?.findViewById<RoundCornersTextureView>(R.id.conference_active_speaker_remote_video)
                    coreContext.core.nativeVideoWindowId = window
                } else {
                    Log.i("[Conference Call] Either not in conference or current layout isn't active speaker, updating Core's native window id")
                    coreContext.core.nativeVideoWindowId = null
                }
            }
        }

        conferenceViewModel.conferenceParticipantDevices.observe(
            viewLifecycleOwner
        ) {
            if (it.size > conferenceViewModel.maxParticipantsForMosaicLayout) {
                showSnackBar(R.string.conference_too_many_participants_for_mosaic_layout)
            }
        }

        conferenceViewModel.conference.observe(
            viewLifecycleOwner
        ) { conference ->
            if (conference != null) switchToFullScreenIfPossible(conference)
        }

        conferenceViewModel.conferenceCreationPending.observe(
            viewLifecycleOwner
        ) { creationPending ->
            if (!creationPending) {
                val conference = conferenceViewModel.conference.value
                if (conference != null) switchToFullScreenIfPossible(conference)
            }
        }

        conferenceViewModel.conferenceDisplayMode.observe(
            viewLifecycleOwner
        ) { layout ->
            when (layout) {
                ConferenceDisplayMode.AUDIO_ONLY -> {
                    controlsViewModel.fullScreenMode.value = false
                }
                else -> {
                    val conference = conferenceViewModel.conference.value
                    if (conference != null) switchToFullScreenIfPossible(conference)
                }
            }
        }

        conferenceViewModel.firstToJoinEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                Snackbar
                    .make(binding.coordinator, R.string.conference_first_to_join, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.primaryButtons.hangup)
                    .show()
            }
        }

        conferenceViewModel.allParticipantsLeftEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                Snackbar
                    .make(binding.coordinator, R.string.conference_last_user, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.primaryButtons.hangup)
                    .show()
            }
        }

        controlsViewModel.goToConferenceParticipantsListEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                navigateToConferenceParticipants()
            }
        }

        controlsViewModel.goToChatEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                goToChat()
            }
        }

        controlsViewModel.goToCallsListEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                navigateToCallsList()
            }
        }

        controlsViewModel.goToConferenceLayoutSettingsEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                navigateToConferenceLayout()
            }
        }

        controlsViewModel.foldingState.observe(
            viewLifecycleOwner
        ) { state ->
            updateHingeRelatedConstraints(state)
        }

        callsViewModel.callUpdateEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume { call ->
                val conference = call.conference
                if (conference != null && conferenceViewModel.conference.value == null) {
                    Log.i("[Conference Call] Found conference attached to call and no conference in dedicated view model, init & configure it")
                    conferenceViewModel.initConference(conference)
                    conferenceViewModel.configureConference(conference)
                }
            }
        }

        controlsViewModel.goToDialerEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume { isCallTransfer ->
                val intent = Intent()
                intent.setClass(requireContext(), MainActivity::class.java)
                intent.putExtra("Dialer", true)
                intent.putExtra("Transfer", isCallTransfer)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }

        binding.stubbedConferenceActiveSpeakerLayout.setOnInflateListener { _, inflated ->
            Log.i("[Conference Call] Active speaker conference layout inflated")
            val binding = DataBindingUtil.bind<ViewDataBinding>(inflated)
            binding?.lifecycleOwner = viewLifecycleOwner
            startTimer(R.id.active_conference_timer)
        }

        binding.stubbedConferenceGridLayout.setOnInflateListener { _, inflated ->
            Log.i("[Conference Call] Mosaic conference layout inflated")
            val binding = DataBindingUtil.bind<ViewDataBinding>(inflated)
            binding?.lifecycleOwner = viewLifecycleOwner
            startTimer(R.id.active_conference_timer)
        }

        binding.stubbedConferenceAudioOnlyLayout.setOnInflateListener { _, inflated ->
            Log.i("[Conference Call] Audio only conference layout inflated")
            val binding = DataBindingUtil.bind<ViewDataBinding>(inflated)
            binding?.lifecycleOwner = viewLifecycleOwner
            startTimer(R.id.active_conference_timer)
        }

        binding.stubbedAudioRoutes.setOnInflateListener { _, inflated ->
            val binding = DataBindingUtil.bind<ViewDataBinding>(inflated)
            binding?.lifecycleOwner = viewLifecycleOwner
        }

        binding.stubbedNumpad.setOnInflateListener { _, inflated ->
            val binding = DataBindingUtil.bind<ViewDataBinding>(inflated)
            binding?.lifecycleOwner = viewLifecycleOwner
        }

        binding.stubbedCallStats.setOnInflateListener { _, inflated ->
            val binding = DataBindingUtil.bind<ViewDataBinding>(inflated)
            binding?.lifecycleOwner = viewLifecycleOwner
        }

        binding.stubbedPausedConference.setOnInflateListener { _, inflated ->
            val binding = DataBindingUtil.bind<ViewDataBinding>(inflated)
            binding?.lifecycleOwner = viewLifecycleOwner
        }
    }

    override fun onPause() {
        super.onPause()

        controlsViewModel.hideExtraButtons(true)
    }

    private fun switchToFullScreenIfPossible(conference: Conference) {
        if (corePreferences.enableFullScreenWhenJoiningVideoConference) {
            if (conference.currentParams.isVideoEnabled) {
                when {
                    conference.me.devices.isEmpty() -> {
                        Log.w("[Conference Call] Conference has video enabled but either our device hasn't joined yet")
                    }
                    conference.me.devices.find { it.getStreamAvailability(StreamType.Video) } != null -> {
                        Log.i("[Conference Call] Conference has video enabled & our device has video enabled, enabling full screen mode")
                        controlsViewModel.fullScreenMode.value = true
                    }
                    else -> {
                        Log.w("[Conference Call] Conference has video enabled but our device video is disabled")
                    }
                }
            }
        }
    }

    private fun goToChat() {
        val intent = Intent()
        intent.setClass(requireContext(), MainActivity::class.java)
        intent.putExtra("Chat", true)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun showSnackBar(resourceId: Int) {
        Snackbar.make(binding.coordinator, resourceId, Snackbar.LENGTH_LONG).show()
    }

    private fun startTimer(timerId: Int) {
        val timer: Chronometer? = binding.root.findViewById(timerId)
        if (timer == null) {
            Log.w("[Conference Call] Timer not found, maybe view wasn't inflated yet?")
            return
        }

        val conference = conferenceViewModel.conference.value
        if (conference != null) {
            val duration = 1000 * conference.duration // Linphone timestamps are in seconds
            timer.base = SystemClock.elapsedRealtime() - duration
        } else {
            Log.e("[Conference Call] Conference not found, timer will have no base")
        }

        timer.start()
    }

    private fun updateHingeRelatedConstraints(state: FoldingFeature.State) {
        Log.i("[Conference Call] Updating constraint layout hinges")
        /*val constraintLayout = binding.constraintLayout
        val set = ConstraintSet()
        set.clone(constraintLayout)

        if (state == FoldingFeature.State.HALF_OPENED) {
            set.setGuidelinePercent(R.id.hinge_top, 0.5f)
            set.setGuidelinePercent(R.id.hinge_bottom, 0.5f)
        } else {
            set.setGuidelinePercent(R.id.hinge_top, 0f)
            set.setGuidelinePercent(R.id.hinge_bottom, 1f)
        }

        set.applyTo(constraintLayout)*/
    }
}
