package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.domain.usecase.LogoutUseCase
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val logoutUseCase: LogoutUseCase
) : ViewModel() {

    val userId: Long get() = authRepository.getUserId()
    val name: String get() = authRepository.getName()
    val nickname: String get() = authRepository.getNickname()

    fun logout(onLogout: () -> Unit) {
        logoutUseCase()
        onLogout()
    }
}
