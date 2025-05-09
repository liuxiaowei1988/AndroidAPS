package app.aaps.pump.omnipod.common.ui.wizard.activation.fragment.info

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import app.aaps.pump.omnipod.common.R
import app.aaps.pump.omnipod.common.di.OmnipodPluginQualifier
import app.aaps.pump.omnipod.common.ui.wizard.activation.viewmodel.info.StartPodActivationViewModel
import app.aaps.pump.omnipod.common.ui.wizard.common.fragment.InfoFragmentBase
import javax.inject.Inject

class StartPodActivationFragment : InfoFragmentBase() {

    @Inject
    @OmnipodPluginQualifier
    lateinit var viewModelFactory: ViewModelProvider.Factory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vm: StartPodActivationViewModel by viewModels { viewModelFactory }
        this.viewModel = vm
    }

    @IdRes
    override fun getNextPageActionId(): Int = R.id.action_startPodActivationFragment_to_initializePodFragment

    override fun getIndex(): Int = 1
}