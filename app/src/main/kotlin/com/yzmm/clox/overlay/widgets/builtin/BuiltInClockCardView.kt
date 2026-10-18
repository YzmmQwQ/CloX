package com.yzmm.clox.overlay.widgets.builtin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.yzmm.clox.R
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class BuiltInClockCardView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : MaterialCardView(ctx, attrs) {

    private val tvTime: TextView
    private val tvDay: TextView
    private val tvDate: TextView
    private val btnDelete: ImageButton
    private val dragHandle: ImageView

    var onDelete: (() -> Unit)? = null
    var onDragStart: ((View) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            // Re-fire at the next full minute
            val msUntilNextMin = 60_000L - System.currentTimeMillis() % 60_000L
            handler.postDelayed(this, msUntilNextMin)
        }
    }

    init {
        radius = resources.getDimension(R.dimen.widget_card_radius)
        LayoutInflater.from(ctx).inflate(R.layout.card_builtin_clock, this, true)
        tvTime    = findViewById(R.id.tvClockTime)
        tvDay     = findViewById(R.id.tvClockDay)
        tvDate    = findViewById(R.id.tvClockDate)
        btnDelete = findViewById(R.id.btnDelete)
        dragHandle = findViewById(R.id.dragHandle)

        btnDelete.setOnClickListener { onDelete?.invoke() }
        dragHandle.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { onDragStart?.invoke(v); true } else false
        }
        updateTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(ticker)
    }

    fun setEditMode(editing: Boolean) {
        val vis = if (editing) View.VISIBLE else View.GONE
        btnDelete.visibility = vis
        dragHandle.visibility = vis
    }

    private fun updateTime() {
        val cal = Calendar.getInstance()
        tvTime.text = String.format(Locale.getDefault(), "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        tvDay.text  = SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.time)
        tvDate.text = SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
    }
}
