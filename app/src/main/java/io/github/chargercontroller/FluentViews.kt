package io.github.chargercontroller

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.roundToInt

internal object Fluent {
    val ink = Color.rgb(36, 36, 36)
    val muted = Color.rgb(97, 97, 97)
    val blue = Color.rgb(15, 108, 189)
    val soft = Color.rgb(235, 243, 252)
    val canvas = Color.rgb(245, 245, 245)
    val line = Color.rgb(224, 224, 224)
    val red = Color.rgb(177, 14, 28)
    fun dp(c: Context, n: Int) = (c.resources.displayMetrics.density * n).roundToInt()
    fun surface(c: Context, color: Int = Color.WHITE, radius: Int = 12, stroke: Int? = line) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radius).toFloat()
        stroke?.let { setStroke(dp(c, 1), it) }
    }
}

/** Code-drawn line icons: no network assets, font glyphs or emoji. */
internal class FluentIcon @JvmOverloads constructor(context: Context, private val name: String = "info", var tint: Int = Fluent.muted) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val rect = RectF()
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save(); c.translate(width / 2f - 12 * width / 24f, height / 2f - 12 * height / 24f)
        c.scale(width / 24f, height / 24f)
        val p = stroke.apply { color = tint; style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun path(vararg points: Float) { val v = shape; v.reset(); v.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) v.lineTo(points[i], points[i + 1]); c.drawPath(v, p) }
        when (name) {
            "close" -> {path(6f,6f,18f,18f);path(18f,6f,6f,18f)}
            "check" -> path(5f,12f,10f,17f,19f,7f)
            "file" -> {path(6f,3f,15f,3f,19f,7f,19f,21f,5f,21f,5f,3f,6f,3f);path(15f,3f,15f,8f,19f,8f);path(9f,12f,15f,12f);path(9f,16f,15f,16f)}
            "home" -> { path(3f,10f,12f,3f,21f,10f); path(5f,9f,5f,21f,10f,21f,10f,14f,14f,14f,14f,21f,19f,21f,19f,9f) }
            "shield" -> { path(12f,3f,20f,6f,19f,15f,16f,19f,12f,22f,8f,19f,5f,15f,4f,6f,12f,3f); path(8f,12f,11f,15f,16f,10f) }
            "settings" -> { path(9f,3f,10f,1f,14f,1f,15f,3f,18f,5f,21f,5f,23f,9f,21f,11f,21f,13f,23f,15f,21f,19f,18f,19f,15f,21f,14f,23f,10f,23f,9f,21f,6f,19f,3f,19f,1f,15f,3f,13f,3f,11f,1f,9f,3f,5f,6f,5f,9f,3f);c.drawCircle(12f,12f,3.5f,p) }
            "plug" -> { path(6f,9f,18f,9f,18f,13f,15f,17f,9f,17f,6f,13f,6f,9f); path(9f,4f,9f,9f); path(15f,4f,15f,9f); path(12f,17f,12f,22f) }
            "user" -> { c.drawCircle(12f,7f,4f,p); c.drawArc(rect.apply{set(4f,14f,20f,27f)},180f,180f,false,p) }
            "history" -> { c.drawArc(rect.apply{set(4f,4f,21f,21f)},210f,310f,false,p); path(3f,4f,3f,10f,9f,10f); path(12f,8f,12f,13f,16f,15f) }
            "wifi" -> { c.drawArc(rect.apply{set(1f,4f,23f,24f)},225f,90f,false,p); c.drawArc(rect.apply{set(5f,9f,19f,23f)},225f,90f,false,p); c.drawCircle(12f,18f,1f,p) }
            "cloud" -> { val v=shape; v.reset(); v.moveTo(5f,18f); v.cubicTo(-1f,17f,1f,10f,6f,11f); v.cubicTo(6f,2f,18f,3f,18f,10f); v.cubicTo(24f,10f,25f,18f,19f,18f); v.close(); c.drawPath(v,p) }
            "bolt" -> path(14f,2f,5f,14f,11f,14f,10f,22f,19f,10f,13f,10f,14f,2f)
            "bell" -> { path(5f,17f,7f,14f,7f,8f,10f,5f,14f,5f,17f,8f,17f,14f,19f,17f,5f,17f); c.drawArc(rect.apply{set(10f,18f,14f,22f)},0f,180f,false,p) }
            "sliders" -> { path(3f,6f,21f,6f); path(3f,17f,21f,17f); p.style=Paint.Style.FILL; p.color=Color.WHITE; c.drawCircle(8f,6f,3f,p); c.drawCircle(16f,17f,3f,p); p.style=Paint.Style.STROKE;p.color=tint;c.drawCircle(8f,6f,3f,p);c.drawCircle(16f,17f,3f,p) }
            "down" -> { path(12f,3f,12f,20f);path(6f,14f,12f,20f,18f,14f) }
            "up" -> { path(12f,21f,12f,4f);path(6f,10f,12f,4f,18f,10f) }
            "back" -> path(15f,5f,8f,12f,15f,19f)
            "info" -> { c.drawCircle(12f,12f,9f,p);path(12f,11f,12f,17f);c.drawPoint(12f,7f,p) }
            else -> path(9f,6f,15f,12f,9f,18f)
        }
        c.restore()
    }
}

internal class BatteryGauge(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    var percent: Int? = null
        set(value) { field = value; contentDescription = "手机电量：${value?.let { "$it%" } ?: "未知"}"; invalidate() }
    var plugged = false
        set(value) { field = value; invalidate() }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val side = minOf(width, height).toFloat(); val cx=width/2f;val cy=height/2f
        val radius=side/2-10*d
        val p=paint.apply { reset();isAntiAlias=true;style=Paint.Style.STROKE;strokeWidth=6*d;color=Color.rgb(235,235,235);strokeCap=Paint.Cap.ROUND }
        c.drawCircle(cx,cy,radius,p); p.color=Fluent.blue
        percent?.let { c.drawArc(bounds.apply{set(cx-radius,cy-radius,cx+radius,cy+radius)},-90f,it*3.6f,false,p) }
        p.style=Paint.Style.FILL;p.color=Fluent.ink;p.textSize=62*d;p.typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)
        p.textAlign=Paint.Align.LEFT
        val number=percent?.toString() ?: "—"; val w=p.measureText(number)
        p.textSize=20*d;val suffixWidth=p.measureText("%")
        val start=cx-(w+suffixWidth+3*d)/2
        p.textSize=62*d;c.drawText(number,start,cy+12*d,p)
        p.textSize=20*d;c.drawText("%",start+w+3*d,cy+12*d,p)
        p.textSize=11*d;p.color=Fluent.muted;p.typeface=Typeface.DEFAULT;p.textAlign=Paint.Align.CENTER
        c.drawText(if(plugged) "已连接电源" else "未连接电源",cx,cy+39*d,p)
    }
}

/** Two independent accessible handles on a shared rail. Does not persist or control a plug. */
internal class ThresholdRangeView(context: Context) : ViewGroup(context) {
    var low = 30; private set
    var high = 80; private set
    var onValuesChanged: ((Int, Int) -> Unit)? = null
    private var draggingLow = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lower = handle(true)
    private val upper = handle(false)
    init { setWillNotDraw(false); minimumHeight = Fluent.dp(context,48); addView(lower);addView(upper) }
    fun values(low: Int, high: Int) { this.high=high.coerceIn(2,100);this.low=low.coerceIn(1,this.high-1);requestLayout();invalidate();updateDescription() }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled);lower.isEnabled=enabled;upper.isEnabled=enabled;alpha=if(enabled)1f else .45f }
    private fun updateDescription() { lower.contentDescription="低电量开启阈值，$low%";upper.contentDescription="高电量关闭阈值，$high%" }
    private fun handle(isLow: Boolean) = View(context).apply {
        isFocusable=true; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        tag=if(isLow)"threshold-low-handle" else "threshold-high-handle"
        background=Fluent.surface(context,Fluent.blue,20,Color.WHITE)
        elevation=Fluent.dp(context,2).toFloat()
        accessibilityDelegate=object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info);info.className=SeekBar::class.java.name
                info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,if(isLow)1f else (low+1).toFloat(),if(isLow)(high-1).toFloat() else 100f,(if(isLow)low else high).toFloat())
                if(isEnabled) { info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if(!this@ThresholdRangeView.isEnabled)return false
                val current=if(isLow)low else high
                val target=when(action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> current+1
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> current-1
                    android.R.id.accessibilityActionSetProgress -> args?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.roundToInt() ?: return false
                    else -> return super.performAccessibilityAction(host,action,args)
                }
                move(isLow,target);return true
            }
        }
        setOnKeyListener { _,code,event ->
            if(!this@ThresholdRangeView.isEnabled||event.action!=KeyEvent.ACTION_DOWN)false
            else when(code) {
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_DOWN -> { move(isLow,(if(isLow)low else high)-1);true }
                KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP -> { move(isLow,(if(isLow)low else high)+1);true }
                else -> false
            }
        }
    }
    private fun x(value:Int)=Fluent.dp(context,12)+(width-Fluent.dp(context,24))*(value-1)/99f
    override fun onMeasure(w:Int,h:Int) { setMeasuredDimension(MeasureSpec.getSize(w),resolveSize(Fluent.dp(context,48),h));val size=MeasureSpec.makeMeasureSpec(Fluent.dp(context,22),MeasureSpec.EXACTLY);lower.measure(size,size);upper.measure(size,size) }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        for((v,n) in listOf(lower to low,upper to high)) { val x=x(n).roundToInt()-v.measuredWidth/2;val y=(height-v.measuredHeight)/2;v.layout(x,y,x+v.measuredWidth,y+v.measuredHeight) }
    }
    override fun onDraw(c:Canvas) {
        val p=paint.apply { strokeWidth=Fluent.dp(context,4).toFloat();strokeCap=Paint.Cap.ROUND;color=Color.rgb(209,209,209) }
        c.drawLine(x(1),height/2f,x(100),height/2f,p);p.color=Fluent.blue
        if(low<high)c.drawLine(x(low),height/2f,x(high),height/2f,p)
    }
    private fun move(isLow:Boolean,value:Int) {
        if(isLow)low=value.coerceIn(1,(high-1).coerceAtLeast(1)) else high=value.coerceIn((low+1).coerceAtMost(100),100)
        values(low,high);onValuesChanged?.invoke(low,high)
    }
    override fun onInterceptTouchEvent(event:MotionEvent)=isEnabled
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(windowToken,0);parent.requestDisallowInterceptTouchEvent(true);draggingLow=abs(e.x-x(low))<=abs(e.x-x(high));(if(draggingLow)lower else upper).requestFocus() }
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false);if(e.actionMasked==MotionEvent.ACTION_CANCEL)return true;performClick() }
            else -> return false
        }
        val span=(width-Fluent.dp(context,24)).coerceAtLeast(1)
        move(draggingLow,(1+(e.x-Fluent.dp(context,12))/span*99).roundToInt());return true
    }
    override fun performClick():Boolean { super.performClick();return true }
}
