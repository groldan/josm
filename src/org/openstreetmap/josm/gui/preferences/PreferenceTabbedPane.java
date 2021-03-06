// License: GPL. Copyright 2007 by Immanuel Scholz and others
package org.openstreetmap.josm.gui.preferences;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.ExpertToggleAction;
import org.openstreetmap.josm.actions.ExpertToggleAction.ExpertModeChangeListener;
import org.openstreetmap.josm.gui.preferences.advanced.AdvancedPreference;
import org.openstreetmap.josm.gui.preferences.display.ColorPreference;
import org.openstreetmap.josm.gui.preferences.display.DisplayPreference;
import org.openstreetmap.josm.gui.preferences.display.DrawingPreference;
import org.openstreetmap.josm.gui.preferences.display.LafPreference;
import org.openstreetmap.josm.gui.preferences.display.LanguagePreference;
import org.openstreetmap.josm.gui.preferences.imagery.ImageryPreference;
import org.openstreetmap.josm.gui.preferences.map.BackupPreference;
import org.openstreetmap.josm.gui.preferences.map.MapPaintPreference;
import org.openstreetmap.josm.gui.preferences.map.MapPreference;
import org.openstreetmap.josm.gui.preferences.map.TaggingPresetPreference;
import org.openstreetmap.josm.gui.preferences.projection.ProjectionPreference;
import org.openstreetmap.josm.gui.preferences.shortcut.ShortcutPreference;
import org.openstreetmap.josm.plugins.PluginDownloadTask;
import org.openstreetmap.josm.plugins.PluginHandler;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.BugReportExceptionHandler;
import org.openstreetmap.josm.tools.CheckParameterUtil;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * The preference settings.
 *
 * @author imi
 */
public class PreferenceTabbedPane extends JTabbedPane implements MouseWheelListener, ExpertModeChangeListener, ChangeListener {
    /**
     * Allows PreferenceSettings to do validation of entered values when ok was pressed.
     * If data is invalid then event can return false to cancel closing of preferences dialog.
     *
     */
    public interface ValidationListener {
        /**
         *
         * @return True if preferences can be saved
         */
        boolean validatePreferences();
    }

    private static interface PreferenceTab {
        public TabPreferenceSetting getTabPreferenceSetting();
        public Component getComponent();
    }

    public static class PreferencePanel extends JPanel implements PreferenceTab {
        private final TabPreferenceSetting preferenceSetting;

        private PreferencePanel(TabPreferenceSetting preferenceSetting) {
            super(new GridBagLayout());
            CheckParameterUtil.ensureParameterNotNull(preferenceSetting);
            this.preferenceSetting = preferenceSetting;
            buildPanel();
        }

        protected void buildPanel() {
            setBorder(BorderFactory.createEmptyBorder(5,5,5,5));
            add(new JLabel(preferenceSetting.getTitle()), GBC.eol().insets(0,5,0,10).anchor(GBC.NORTHWEST));

            JLabel descLabel = new JLabel("<html>"+preferenceSetting.getDescription()+"</html>");
            descLabel.setFont(descLabel.getFont().deriveFont(Font.ITALIC));
            add(descLabel, GBC.eol().insets(5,0,5,20).fill(GBC.HORIZONTAL));
        }

        @Override
        public final TabPreferenceSetting getTabPreferenceSetting() {
            return preferenceSetting;
        }

        @Override
        public Component getComponent() {
            return this;
        }
    }

    public static class PreferenceScrollPane extends JScrollPane implements PreferenceTab {
        private final TabPreferenceSetting preferenceSetting;

        private PreferenceScrollPane(Component view, TabPreferenceSetting preferenceSetting) {
            super(view);
            this.preferenceSetting = preferenceSetting;
        }

        private PreferenceScrollPane(PreferencePanel preferencePanel) {
            super(preferencePanel.getComponent());
            this.preferenceSetting = preferencePanel.getTabPreferenceSetting();
        }

        @Override
        public final TabPreferenceSetting getTabPreferenceSetting() {
            return preferenceSetting;
        }

        @Override
        public Component getComponent() {
            return this;
        }
    }

    // all created tabs
    private final List<PreferenceTab> tabs = new ArrayList<PreferenceTab>();
    private final static Collection<PreferenceSettingFactory> settingsFactory = new LinkedList<PreferenceSettingFactory>();
    private final List<PreferenceSetting> settings = new ArrayList<PreferenceSetting>();

    // distinct list of tabs that have been initialized (we do not initialize tabs until they are displayed to speed up dialog startup)
    private final List<PreferenceSetting> settingsInitialized = new ArrayList<PreferenceSetting>();

    List<ValidationListener> validationListeners = new ArrayList<ValidationListener>();

    /**
     * Add validation listener to currently open preferences dialog. Calling to removeValidationListener is not necessary, all listeners will
     * be automatically removed when dialog is closed
     * @param validationListener
     */
    public void addValidationListener(ValidationListener validationListener) {
        validationListeners.add(validationListener);
    }

    /**
     * Construct a PreferencePanel for the preference settings. Layout is GridBagLayout
     * and a centered title label and the description are added.
     * @return The created panel ready to add other controls.
     */
    public PreferencePanel createPreferenceTab(TabPreferenceSetting caller) {
        return createPreferenceTab(caller, false);
    }

    /**
     * Construct a PreferencePanel for the preference settings. Layout is GridBagLayout
     * and a centered title label and the description are added.
     * @param inScrollPane if <code>true</code> the added tab will show scroll bars
     *        if the panel content is larger than the available space
     * @return The created panel ready to add other controls.
     */
    public PreferencePanel createPreferenceTab(TabPreferenceSetting caller, boolean inScrollPane) {
        CheckParameterUtil.ensureParameterNotNull(caller);
        PreferencePanel p = new PreferencePanel(caller);

        PreferenceTab tab = p;
        if (inScrollPane) {
            PreferenceScrollPane sp = new PreferenceScrollPane(p);
            tab = sp;
        }
        tabs.add(tab);
        return p;
    }

    private static interface TabIdentifier {
        public boolean identify(TabPreferenceSetting tps, Object param);
    }

    private void selectTabBy(TabIdentifier method, Object param) {
        for (int i=0; i<getTabCount(); i++) {
            Component c = getComponentAt(i);
            if (c instanceof PreferenceTab) {
                PreferenceTab tab = (PreferenceTab) c;
                if (method.identify(tab.getTabPreferenceSetting(), param)) {
                    setSelectedIndex(i);
                    return;
                }
            }
        }
    }

    public void selectTabByName(String name) {
        selectTabBy(new TabIdentifier(){
            @Override
            public boolean identify(TabPreferenceSetting tps, Object name) {
                return tps.getIconName().equals(name);
            }}, name);
    }

    public void selectTabByPref(Class<? extends TabPreferenceSetting> clazz) {
        selectTabBy(new TabIdentifier(){
            @Override
            public boolean identify(TabPreferenceSetting tps, Object clazz) {
                return tps.getClass().isAssignableFrom((Class<?>) clazz);
            }}, clazz);
    }

    public final DisplayPreference getDisplayPreference() {
        return getSetting(DisplayPreference.class);
    }

    public final MapPreference getMapPreference() {
        return getSetting(MapPreference.class);
    }

    public final PluginPreference getPluginPreference() {
        return getSetting(PluginPreference.class);
    }

    public final ImageryPreference getImageryPreference() {
        return getSetting(ImageryPreference.class);
    }

    public void savePreferences() {
        if(Main.applet)
            return;
        // create a task for downloading plugins if the user has activated, yet not downloaded,
        // new plugins
        //
        final PluginPreference preference = getPluginPreference();
        final List<PluginInformation> toDownload = preference.getPluginsScheduledForUpdateOrDownload();
        final PluginDownloadTask task;
        if (toDownload != null && ! toDownload.isEmpty()) {
            task = new PluginDownloadTask(this, toDownload, tr("Download plugins"));
        } else {
            task = null;
        }

        // this is the task which will run *after* the plugins are downloaded
        //
        final Runnable continuation = new Runnable() {
            public void run() {
                boolean requiresRestart = false;
                if (task != null && !task.isCanceled()) {
                    if (!task.getDownloadedPlugins().isEmpty()) {
                        requiresRestart = true;
                    }
                }

                for (PreferenceSetting setting : settingsInitialized) {
                    if (setting.ok()) {
                        requiresRestart = true;
                    }
                }

                // build the messages. We only display one message, including the status
                // information from the plugin download task and - if necessary - a hint
                // to restart JOSM
                //
                StringBuilder sb = new StringBuilder();
                sb.append("<html>");
                if (task != null && !task.isCanceled()) {
                    sb.append(PluginPreference.buildDownloadSummary(task));
                }
                if (requiresRestart) {
                    sb.append(tr("You have to restart JOSM for some settings to take effect."));
                }
                sb.append("</html>");

                // display the message, if necessary
                //
                if ((task != null && !task.isCanceled()) || requiresRestart) {
                    JOptionPane.showMessageDialog(
                            Main.parent,
                            sb.toString(),
                            tr("Warning"),
                            JOptionPane.WARNING_MESSAGE
                            );
                }
                Main.parent.repaint();
            }
        };

        if (task != null) {
            // if we have to launch a plugin download task we do it asynchronously, followed
            // by the remaining "save preferences" activites run on the Swing EDT.
            //
            Main.worker.submit(task);
            Main.worker.submit(
                    new Runnable() {
                        public void run() {
                            SwingUtilities.invokeLater(continuation);
                        }
                    }
                    );
        } else {
            // no need for asynchronous activities. Simply run the remaining "save preference"
            // activities on this thread (we are already on the Swing EDT
            //
            continuation.run();
        }
    }

    /**
     * If the dialog is closed with Ok, the preferences will be stored to the preferences-
     * file, otherwise no change of the file happens.
     */
    public PreferenceTabbedPane() {
        super(JTabbedPane.LEFT, JTabbedPane.SCROLL_TAB_LAYOUT);
        super.addMouseWheelListener(this);
        super.getModel().addChangeListener(this);
        ExpertToggleAction.addExpertModeChangeListener(this);
    }

    public void buildGui() {
        for (PreferenceSettingFactory factory : settingsFactory) {
            PreferenceSetting setting = factory.createPreferenceSetting();
            if (setting != null) {
                settings.add(setting);
            }
        }
        addGUITabs(false);
    }

    private void addGUITabsForSetting(Icon icon, TabPreferenceSetting tps) {
        for (PreferenceTab tab : tabs) {
            if (tab.getTabPreferenceSetting().equals(tps)) {
                insertGUITabsForSetting(icon, tps, getTabCount());
            }
        }
    }

    private void insertGUITabsForSetting(Icon icon, TabPreferenceSetting tps, int index) {
        int position = index;
        for (PreferenceTab tab : tabs) {
            if (tab.getTabPreferenceSetting().equals(tps)) {
                insertTab(null, icon, tab.getComponent(), tps.getTooltip(), position++);
            }
        }
    }

    private void addGUITabs(boolean clear) {
        boolean expert = ExpertToggleAction.isExpert();
        Component sel = getSelectedComponent();
        if (clear) {
            removeAll();
        }
        // Inspect each tab setting
        for (PreferenceSetting setting : settings) {
            if (setting instanceof TabPreferenceSetting) {
                TabPreferenceSetting tps = (TabPreferenceSetting) setting;
                if (expert || !tps.isExpert()) {
                    // Get icon
                    String iconName = tps.getIconName();
                    ImageIcon icon = iconName != null && iconName.length() > 0 ? ImageProvider.get("preferences", iconName) : null;
                    // See #6985 - Force icons to be 48x48 pixels
                    if (icon != null && (icon.getIconHeight() != 48 || icon.getIconWidth() != 48)) {
                        icon = new ImageIcon(icon.getImage().getScaledInstance(48, 48, Image.SCALE_DEFAULT));
                    }
                    if (settingsInitialized.contains(tps)) {
                        // If it has been initialized, add corresponding tab(s)
                        addGUITabsForSetting(icon, tps);
                    } else {
                        // If it has not been initialized, create an empty tab with only icon and tooltip
                        addTab(null, icon, new PreferencePanel(tps), tps.getTooltip());
                    }
                }
            }
        }
        try {
            if (sel != null) {
                setSelectedComponent(sel);
            }
        } catch (IllegalArgumentException e) {}
    }

    @Override
    public void expertChanged(boolean isExpert) {
        addGUITabs(true);
    }

    public List<PreferenceSetting> getSettings() {
        return settings;
    }

    @SuppressWarnings("unchecked")
    public <T>  T getSetting(Class<? extends T> clazz) {
        for (PreferenceSetting setting:settings) {
            if (clazz.isAssignableFrom(setting.getClass()))
                return (T)setting;
        }
        return null;
    }

    static {
        // order is important!
        settingsFactory.add(new DisplayPreference.Factory());
        settingsFactory.add(new DrawingPreference.Factory());
        settingsFactory.add(new ColorPreference.Factory());
        settingsFactory.add(new LafPreference.Factory());
        settingsFactory.add(new LanguagePreference.Factory());
        settingsFactory.add(new ServerAccessPreference.Factory());
        settingsFactory.add(new MapPreference.Factory());
        settingsFactory.add(new ProjectionPreference.Factory());
        settingsFactory.add(new MapPaintPreference.Factory());
        settingsFactory.add(new TaggingPresetPreference.Factory());
        settingsFactory.add(new BackupPreference.Factory());
        if(!Main.applet) {
            settingsFactory.add(new PluginPreference.Factory());
        }
        settingsFactory.add(Main.toolbar);
        settingsFactory.add(new AudioPreference.Factory());
        settingsFactory.add(new ShortcutPreference.Factory());
        settingsFactory.add(new ValidatorPreference.Factory());
        settingsFactory.add(new RemoteControlPreference.Factory());
        settingsFactory.add(new ImageryPreference.Factory());

        PluginHandler.getPreferenceSetting(settingsFactory);

        // always the last: advanced tab
        settingsFactory.add(new AdvancedPreference.Factory());
    }

    /**
     * This mouse wheel listener reacts when a scroll is carried out over the
     * tab strip and scrolls one tab/down or up, selecting it immediately.
     */
    public void mouseWheelMoved(MouseWheelEvent wev) {
        // Ensure the cursor is over the tab strip
        if(super.indexAtLocation(wev.getPoint().x, wev.getPoint().y) < 0)
            return;

        // Get currently selected tab
        int newTab = super.getSelectedIndex() + wev.getWheelRotation();

        // Ensure the new tab index is sound
        newTab = newTab < 0 ? 0 : newTab;
        newTab = newTab >= super.getTabCount() ? super.getTabCount() - 1 : newTab;

        // select new tab
        super.setSelectedIndex(newTab);
    }

    @Override
    public void stateChanged(ChangeEvent e) {
        int index = getSelectedIndex();
        Component sel = getSelectedComponent();
        if (index > -1 && sel instanceof PreferenceTab) {
            PreferenceTab tab = (PreferenceTab) sel;
            TabPreferenceSetting preferenceSettings = tab.getTabPreferenceSetting();
            if (!settingsInitialized.contains(preferenceSettings)) {
                try {
                    getModel().removeChangeListener(this);
                    preferenceSettings.addGui(this);
                    // Add GUI for sub preferences
                    for (PreferenceSetting setting : settings) {
                        if (setting instanceof SubPreferenceSetting) {
                            SubPreferenceSetting sps = (SubPreferenceSetting) setting;
                            if (sps.getTabPreferenceSetting(this) == preferenceSettings) {
                                try {
                                    sps.addGui(this);
                                } catch (SecurityException ex) {
                                    ex.printStackTrace();
                                } catch (Throwable ex) {
                                    BugReportExceptionHandler.handleException(ex);
                                } finally {
                                    settingsInitialized.add(sps);
                                }
                            }
                        }
                    }
                    Icon icon = getIconAt(index);
                    remove(index);
                    insertGUITabsForSetting(icon, preferenceSettings, index);
                    setSelectedIndex(index);
                } catch (SecurityException ex) {
                    ex.printStackTrace();
                } catch (Throwable ex) {
                    // allow to change most settings even if e.g. a plugin fails
                    BugReportExceptionHandler.handleException(ex);
                } finally {
                    settingsInitialized.add(preferenceSettings);
                    getModel().addChangeListener(this);
                }
            }
        }
    }
}
