package com.woory.presentation.ui.promiseinfo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.skt.tmap.TMapView
import com.woory.presentation.R
import com.woory.presentation.databinding.FragmentPromiseInfoBinding
import com.woory.presentation.ui.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PromiseInfoFragment :
    BaseFragment<FragmentPromiseInfoBinding>(R.layout.fragment_promise_info) {

    private val viewModel: PromiseInfoViewModel by activityViewModels()
    private lateinit var tMapView: TMapView
    private val participantAdapter by lazy {
        PromiseUserAdapter(viewModel)
    }

    private val clipBoard by lazy {
        requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tMapView = binding.mapPromiseLocation

        viewModel.fetchPromiseDate()
        binding.apply {
            lifecycleOwner = viewLifecycleOwner
            vm = viewModel
            defaultString = ""
        }

        binding.rvPromiseParticipant.adapter = participantAdapter
        participantAdapter.submitList(viewModel.dummyUsers)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.errorState.collect {
                if (it) {
                    makeSnackBar(getString(R.string.promise_fetch_fail))
                    viewModel.setUiState(PromiseUiState.Loading)
                    viewModel.setErrorState(false)
                }
            }
        }

        binding.btnCodeCopy.setOnClickListener {
            val clip = ClipData.newPlainText("", viewModel.gameCode.value)
            clipBoard.setPrimaryClip(clip)
            makeSnackBar(getString(R.string.copy_complete))
        }
    }

    private fun makeSnackBar(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }
}