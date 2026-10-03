package com.cashcraft.ads.mediation

import android.os.Looper
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Call from onViewCreated after Ads.initialize. Owns only the Banner it adds to [container].
 * Equal bindings reuse the View; changed requests replace it. The Fragment View owner releases it.
 */
fun Fragment.bindBanner(
    container: ViewGroup,
    position: String,
    size: BannerSize = BannerSize.AnchoredAdaptive,
    active: Boolean = true,
    onState: (BannerState) -> Unit = {},
): AdsBannerView {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Banner binding requires the main thread" }
    val owner = viewLifecycleOwner
    check(owner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "Cannot bind a Banner to a destroyed Fragment View" }
    val request = Ads.bannerRequest(position, size)
    val previous = container.getTag(R.id.ads_banner_binding) as? BannerBinding
    previous?.view?.let { view ->
        if (previous.owner === owner && view.request == request) {
            view.onState = onState
            view.setActive(active)
            return view
        }
    }

    val binding = BannerBinding(container, owner)
    // Publish ownership before releasing the old View: state callbacks may bind again.
    container.setTag(R.id.ads_banner_binding, binding)
    previous?.dispose()
    val banner = AdsBannerView(requireActivity(), owner, request, active && binding.isCurrent, onState)
    binding.view = banner
    banner.setOnDestroyed(binding::dispose)
    if (binding.isCurrent && owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
        container.addView(banner, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    } else {
        binding.dispose()
    }
    return banner
}

private class BannerBinding(
    private val container: ViewGroup,
    val owner: LifecycleOwner,
) {
    var view: AdsBannerView? = null
    val isCurrent: Boolean get() = container.getTag(R.id.ads_banner_binding) === this

    fun dispose() {
        val previous = view
        view = null
        // Replaced bindings must not deliver a stale Destroyed callback to the page.
        if (!isCurrent) previous?.onState = {}
        previous?.destroy()
        // Let the current callback receive Destroyed, but never clear a binding created by it.
        if (isCurrent) container.setTag(R.id.ads_banner_binding, null)
        if (previous?.parent === container) container.removeView(previous)
    }
}
