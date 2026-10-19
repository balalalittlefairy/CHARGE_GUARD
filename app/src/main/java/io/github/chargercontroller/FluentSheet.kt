package io.github.chargercontroller

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/** HTML prototype's bottom sheet, rendered with native views and real callbacks. */
internal class FluentSheet(private val owner: Activity, val kind: String, title: String, secret: Boolean = false) : Dialog(owner) {
    val content = LinearLayout(owner).apply { orientation=LinearLayout.VERTICAL;tag="sheet-$kind";setPadding(dp(22),dp(9),dp(22),dp(24));background=Fluent.surface(owner,Color.rgb(250,250,250),16,null) }
    val body = LinearLayout(owner).apply { orientation=LinearLayout.VERTICAL }
    private val controls=linkedMapOf<View,Boolean>()
    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val grip=View(owner).apply { background=Fluent.surface(owner,Color.rgb(189,189,189),3,null) }
        content.addView(grip,LinearLayout.LayoutParams(dp(30),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(22) })
        val header=LinearLayout(owner).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(text(title,20,Fluent.ink,true),LinearLayout.LayoutParams(0,-2,1f))
        val close=FrameLayout(owner).apply { tag="sheet-close";isClickable=true;isFocusable=true;contentDescription="关闭弹窗";setOnClickListener{dismiss()} }
        close.addView(FluentIcon(owner,"close"),FrameLayout.LayoutParams(dp(16),dp(16),Gravity.CENTER))
        close.background=Fluent.surface(owner,Color.rgb(235,235,235),20,null)
        header.addView(close,LinearLayout.LayoutParams(dp(30),dp(30)))
        content.addView(header,LinearLayout.LayoutParams(-1,dp(40)).apply { bottomMargin=dp(14) })
        content.addView(body)
        val scroll=object:ScrollView(owner) {
            override fun onMeasure(w:Int,h:Int) { val limit=(owner.resources.displayMetrics.heightPixels*.88f).toInt();val available=if(View.MeasureSpec.getMode(h)==View.MeasureSpec.UNSPECIFIED)limit else View.MeasureSpec.getSize(h);super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(minOf(limit,available),View.MeasureSpec.AT_MOST)) }
        }.apply { isFillViewport=false;isVerticalScrollBarEnabled=false;addView(content) }
        setContentView(scroll)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes=attributes.apply{dimAmount=.30f;windowAnimations=0}
            if(secret)addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setCanceledOnTouchOutside(true)
    }
    override fun show() { super.show();window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT) }
    fun dp(n:Int)=Fluent.dp(owner,n)
    fun text(value:String,size:Int=12,color:Int=Fluent.muted,bold:Boolean=false)=TextView(owner).apply {
        text=value;textSize=size.toFloat();setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)
        if(bold)typeface=Typeface.DEFAULT_BOLD
    }
    fun note(value:String):TextView=text(value,11).also { body.addView(it,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10);bottomMargin=dp(14)}) }
    fun centered(icon:String,title:String,description:String) {
        val mark=FrameLayout(owner).apply{background=Fluent.surface(owner,Fluent.blue,12,null)}
        mark.addView(FluentIcon(owner,icon,Color.WHITE),FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER))
        body.addView(mark,LinearLayout.LayoutParams(dp(60),dp(60)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(12);bottomMargin=dp(14)})
        body.addView(text(title,20,Fluent.ink).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
        body.addView(text(description,12).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(9);bottomMargin=dp(18)})
    }
    fun button(label:String,tag:String,primary:Boolean=true,onClick:()->Unit):Button=Button(owner).apply {
        text=label;this.tag=tag;textSize=13f;isAllCaps=false;minWidth=0;minimumWidth=0;minimumHeight=0;minHeight=0
        setPadding(dp(10),0,dp(10),0);setTextColor(if(primary)Color.WHITE else Fluent.ink)
        background=Fluent.surface(owner,if(primary)Fluent.blue else Color.WHITE,6,if(primary)null else Color.rgb(209,209,209));stateListAnimator=null
        setOnClickListener{onClick()};controls[this]=true
    }
    fun link(label:String,tag:String,onClick:()->Unit):Button=button(label,tag,false,onClick).apply{background=null;setTextColor(Fluent.blue);textSize=11f}
    fun action(label:String,tag:String,primary:Boolean=true,onClick:()->Unit):Button = button(label,tag,primary,onClick).also {
        body.addView(it,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(10)})
    }
    fun actions(first:Button,second:Button) {
        val row=LinearLayout(owner)
        row.addView(first,LinearLayout.LayoutParams(0,dp(44),1f));row.addView(second,LinearLayout.LayoutParams(0,dp(44),1f).apply{leftMargin=dp(10)})
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
    }
    fun field(label:String,value:String,tag:String,secret:Boolean=false,editable:Boolean=true):EditText {
        body.addView(text(label,11),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12);bottomMargin=dp(7)})
        return EditText(owner).apply {
            this.tag=tag;contentDescription=label;setText(value);textSize=13f;setTextColor(Fluent.ink);isSingleLine=true
            setPadding(dp(12),0,dp(12),0);background=Fluent.surface(owner,Color.WHITE,6,Color.rgb(209,209,209))
            inputType=if(secret)android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if(!editable){isFocusable=false;isCursorVisible=false;keyListener=null}
            body.addView(this,LinearLayout.LayoutParams(-1,dp(44)))
        }
    }
    fun row(label:String,value:String) {
        val row=LinearLayout(owner).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(14),0,dp(14))}
        row.addView(text(label,12,Fluent.ink),LinearLayout.LayoutParams(0,-2,1f));row.addView(text(value,12))
        body.addView(row);divider()
    }
    fun divider() { body.addView(View(owner).apply{setBackgroundColor(Fluent.line)},LinearLayout.LayoutParams(-1,dp(1))) }
    fun device(name:String,detail:String,chosen:Boolean,enabled:Boolean,tag:String,onClick:()->Unit) {
        val row=LinearLayout(owner).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(14));background=Fluent.surface(owner,if(chosen)Fluent.soft else Color.WHITE,8,if(chosen)Fluent.blue else Fluent.line);this.tag=tag;isClickable=true;isFocusable=true;isEnabled=enabled;alpha=if(enabled)1f else .5f;setOnClickListener{onClick()}}
        row.addView(FluentIcon(owner,"plug",Fluent.blue),LinearLayout.LayoutParams(dp(22),dp(22)).apply{rightMargin=dp(12)})
        val copy=LinearLayout(owner).apply{orientation=LinearLayout.VERTICAL;addView(text(name,13,Fluent.ink,true));addView(text(detail,10),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})}
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f));row.addView(FluentIcon(owner,if(chosen)"check" else "chevron",Fluent.blue),LinearLayout.LayoutParams(dp(20),dp(20)))
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)});controls[row]=enabled
    }
    fun toggle(title:String,detail:String,checked:Boolean,tag:String,onClick:()->Unit):FluentToggle {
        val row=LinearLayout(owner).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(14),0,dp(14))}
        val copy=LinearLayout(owner).apply{orientation=LinearLayout.VERTICAL;addView(text(title,12,Fluent.ink));addView(text(detail,10),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})}
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        val toggle=FluentToggle(owner).apply{this.checked=checked;this.tag=tag;contentDescription=title;setOnClickListener{onClick()}}
        row.addView(toggle,LinearLayout.LayoutParams(dp(48),dp(44)).apply{leftMargin=dp(10)})
        body.addView(row);divider();return toggle
    }
    fun busy(busy:Boolean) {controls.forEach{(view,allowed)->view.isEnabled=allowed&&!busy;view.alpha=if(view.isEnabled)1f else .45f}}
}

internal class FluentToggle(context:android.content.Context):View(context) {
    var checked=false
        set(value){field=value;invalidate()}
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect=android.graphics.RectF()
    init{isClickable=true;isFocusable=true}
    override fun onDraw(canvas:android.graphics.Canvas) {
        val d=resources.displayMetrics.density;val left=(width-38*d)/2;val top=(height-23*d)/2
        paint.color=if(checked)Fluent.blue else Color.rgb(189,189,189);rect.set(left,top,left+38*d,top+23*d);canvas.drawRoundRect(rect,12*d,12*d,paint)
        paint.color=Color.WHITE;canvas.drawCircle(left+(if(checked)26.5f else 11.5f)*d,top+11.5f*d,8.5f*d,paint)
    }
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo) {super.onInitializeAccessibilityNodeInfo(info);info.className=Switch::class.java.name;info.isCheckable=true;info.isChecked=checked}
}
