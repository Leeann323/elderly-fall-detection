package com.leeann323.fallalert;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class AlertAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private List<Alert> items = new ArrayList<Alert>();

    public AlertAdapter(Context c, List<Alert> initial) {
        this.inflater = LayoutInflater.from(c);
        if (initial != null) this.items = initial;
    }

    public void setItems(List<Alert> list) {
        this.items = list == null ? new ArrayList<Alert>() : list;
        notifyDataSetChanged();
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
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_alert, parent, false);
        }
        Alert a = items.get(position);
        TextView t1 = (TextView) v.findViewById(R.id.tv_title);
        TextView t2 = (TextView) v.findViewById(R.id.tv_sub);
        t1.setText(a.title());
        t2.setText(a.subtitle());
        return v;
    }
}
