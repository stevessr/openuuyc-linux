package org.openuuyc.pico.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

internal object WorkspaceColors {
    val background=Color(0xff161a21)
    val panel=Color(0xff202731)
    val line=Color(0xff2d3542)
    val text=Color(0xffe7ebf2)
    val muted=Color(0xff9ca7b8)
    val accent=Color(0xff4b88eb)
    val selected=Color(0xff22354e)
    val online=Color(0xff52bc91)
    val warning=Color(0xffdfb26c)
}

@Composable
internal fun WorkspaceCard(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Column(modifier.background(WorkspaceColors.panel,RoundedCornerShape(12.dp)).border(1.dp,WorkspaceColors.line,RoundedCornerShape(12.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}

@Composable
internal fun SectionLabel(title: String,detail: String="") {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=PicoTheme.typography.titleMedium,color=WorkspaceColors.text)
        if(detail.isNotBlank()) Text(detail,style=PicoTheme.typography.bodyMedium,color=WorkspaceColors.muted)
    }
}

@Composable
internal fun ChoiceButton(label: String,selected: Boolean=false,enabled: Boolean=true,modifier: Modifier=Modifier,onClick: ()->Unit) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier,colors=ButtonDefaults.buttonColors(
        containerColor=if(selected) WorkspaceColors.selected else WorkspaceColors.panel,
        contentColor=if(selected) WorkspaceColors.accent else WorkspaceColors.text)) {Text(label)}
}

@Composable
internal fun MenuHeader(title: String,subtitle: String="",onClose: ()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title,style=PicoTheme.typography.titleMedium)
            if(subtitle.isNotBlank()) Text(subtitle,style=PicoTheme.typography.bodyMedium,color=WorkspaceColors.muted)
        }
        ChoiceButton("关闭",onClick=onClose)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(WorkspaceColors.line))
}
