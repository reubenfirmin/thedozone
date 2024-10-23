package tdz.view


import tdz.model.Split
import tdz.model.Split.HORIZONTAL
import tdz.model.Split.VERTICAL
//
//class ColumnLayout(val split: Split, columnId: Int, nodes: List<ColumnTreeNode>): ColumnTreeNode, Div() {
//
//    init {
//        id = "layout_${columnId}"
//        style {
//            display = FLEX
//            flexGrow = 1
//            if (split == HORIZONTAL) {
//                flexDirection = COLUMN
//            } else if (split == VERTICAL) {
//                flexDirection = ROW
//            }
//            justifyContent = JustifyContent.START
//            width = 100 to perc
//            height = 100 to perc
//        }
//        nodes.forEach {
//            add(it)
//        }
//    }
//}