package tw.nekomimi.nekogram.config.cell;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.Cells.HeaderCell;

import tw.nekomimi.nekogram.config.CellGroup;
import xyz.nextalone.nagram.ui.CollapsibleHeaderCell;

public class ConfigCellHeader extends AbstractConfigCell {
    private final String title;

    public ConfigCellHeader(String title) {
        this.title = title;
    }

    public int getType() {
        return CellGroup.ITEM_TYPE_HEADER;
    }

    public boolean isEnabled() {
        return false;
    }

    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        if (holder.itemView instanceof CollapsibleHeaderCell) {
            // 折叠分组标题：带展开/收起箭头
            final CollapsibleHeaderCell headerCell = (CollapsibleHeaderCell) holder.itemView;
            headerCell.setTextAndExpanded(title, cellGroup == null || !cellGroup.isCollapsed(this));
            return;
        }
        HeaderCell headerCell = (HeaderCell) holder.itemView;
        headerCell.setText(title);
    }
}
