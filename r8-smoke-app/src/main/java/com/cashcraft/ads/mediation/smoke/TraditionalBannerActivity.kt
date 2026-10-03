package com.cashcraft.ads.mediation.smoke

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cashcraft.ads.mediation.BannerSize
import com.cashcraft.ads.mediation.bindBanner

/** A Fragment View owner is replaced on View recreation, even when its Activity survives. */
class TraditionalBannerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = R.id.banner_fragment_container }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val host = this
            addView(Button(context).apply {
                text = "Replace Fragment View"
                setOnClickListener {
                    supportFragmentManager.beginTransaction()
                        .replace(container.id, TraditionalBannerFragment())
                        .commit()
                }
            })
            // Test controls exercise temporary host changes without replacing the page owner.
            addView(Button(context).apply {
                text = "Parent visibility"
                setOnClickListener {
                    container.visibility = if (container.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            })
            addView(Button(context).apply {
                text = "Detach / reattach container"
                setOnClickListener {
                    if (container.parent == null) {
                        host.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    } else {
                        host.removeView(container)
                    }
                }
            })
            addView(container, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            target.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(container.id, TraditionalBannerFragment())
                .commit()
        }
    }
}

class TraditionalBannerFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val state = TextView(requireContext())
        val bannerContainer = FrameLayout(requireContext())
        val newBanner = bindBanner(
            bannerContainer,
            position = "smoke_fragment",
            size = if (requireActivity().intent.getBooleanExtra("standard_banner", false)) BannerSize.Standard320x50
                else BannerSize.AnchoredAdaptive,
        ) { state.text = it.toString() }
        var narrow = false
        (view as FrameLayout).addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(state)
            addView(Button(context).apply {
                text = "Narrow / full width"
                setOnClickListener {
                    narrow = !narrow
                    val padding = if (narrow) (24 * resources.displayMetrics.density).toInt() else 0
                    newBanner.setPadding(padding, 0, padding, 0)
                }
            })
        })
        view.addView(
            bannerContainer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

}
