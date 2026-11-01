package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

// exteraGram-compatible template fragment. Plugins implement the delegate in
// Python (dynamic_proxy) and present an instance of this fragment.
public class UniversalFragment extends org.telegram.ui.Components.UniversalFragment {

    public interface UniversalFragmentDelegate {
        CharSequence getTitle();

        void beforeCreateView();

        void afterCreateView(View view);

        void onFragmentCreate();

        void onFragmentDestroy();

        boolean onMenuItemClick(int id);

        void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);

        void onClick(UItem item, View view, int position, float x, float y);
    }

    private final UniversalFragmentDelegate delegate;

    public UniversalFragment(UniversalFragmentDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public View createView(Context context) {
        try {
            if (delegate != null) delegate.beforeCreateView();
        } catch (Throwable t) { FileLog.e(t); }
        View view = super.createView(context);
        try {
            if (delegate != null) delegate.afterCreateView(view);
        } catch (Throwable t) { FileLog.e(t); }
        return view;
    }

    @Override
    public boolean onFragmentCreate() {
        try {
            if (delegate != null) delegate.onFragmentCreate();
        } catch (Throwable t) { FileLog.e(t); }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        try {
            if (delegate != null) delegate.onFragmentDestroy();
        } catch (Throwable t) { FileLog.e(t); }
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence getTitle() {
        try {
            if (delegate != null) {
                CharSequence t = delegate.getTitle();
                if (t != null) return t;
            }
        } catch (Throwable t) { FileLog.e(t); }
        return "";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        try {
            if (delegate != null) delegate.fillItems(items, adapter);
        } catch (Throwable t) { FileLog.e(t); }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        try {
            if (delegate != null) delegate.onClick(item, view, position, x, y);
        } catch (Throwable t) { FileLog.e(t); }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
