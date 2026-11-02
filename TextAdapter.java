import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class SimpleTextAdapter extends BaseAdapter {

    private final Context context;
    private final List<String> items;
    private final LayoutInflater inflater;

    public SimpleTextAdapter(Context context, List<String> items) {
        this.context = context;
        this.items = items;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_list, parent, false);
            holder = new ViewHolder();
            holder.textItem = convertView.findViewById(R.id.textItem);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        String currentText = items.get(position);
        holder.textItem.setText(currentText);

        return convertView;
    }

    private static class ViewHolder {
        TextView textItem;
    }
}
