package io.insightdashboard.jmeter;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.ListSelectionEvent;

/**
 * Lets the person navigate the remote host's directory structure (via the
 * already-connected session's own SFTP channel) and pick a path, instead of
 * typing one out by hand with no way to confirm it's actually right. Works
 * for both picking an existing file and picking/typing a path that doesn't
 * exist yet (an output CSV or log path the remote run will create) - the
 * path field at the bottom is always directly editable, and double-clicking
 * a directory descends into it while single-clicking anything just fills
 * that field without navigating, so the two needs don't require two
 * different dialogs.
 */
final class RemoteFileBrowserDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final RemoteRunController controller;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> fileList = new JList<>(listModel);
    private final JTextField currentDirField = new JTextField();
    private final JTextField selectedPathField = new JTextField();
    private final JLabel statusLabel = new JLabel(" ");

    private List<RemoteRunController.RemoteFileEntry> currentEntries = java.util.Collections.emptyList();
    private String result;

    RemoteFileBrowserDialog(Window owner, RemoteRunController controller, String startPath) {
        super(owner, "Browse Remote Host", ModalityType.APPLICATION_MODAL);
        this.controller = controller;
        setSize(560, 480);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(0, 6));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.add(new JLabel("Directory:"), BorderLayout.WEST);
        top.add(currentDirField, BorderLayout.CENTER);
        JButton goButton = new JButton("Go");
        goButton.addActionListener(e -> navigateTo(currentDirField.getText().trim()));
        currentDirField.addActionListener(e -> navigateTo(currentDirField.getText().trim()));
        JButton upButton = new JButton("Up");
        upButton.addActionListener(e -> navigateUp());
        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        topButtons.add(upButton);
        topButtons.add(goButton);
        top.add(topButtons, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        fileList.addListSelectionListener(this::onSelectionChanged);
        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = fileList.locationToIndex(e.getPoint());
                    if (index >= 0 && index < currentEntries.size()
                            && currentEntries.get(index).directory) {
                        navigateTo(joinPath(currentDirField.getText().trim(), currentEntries.get(index).name));
                    }
                }
            }
        });
        add(new JScrollPane(fileList), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        JPanel pathRow = new JPanel(new BorderLayout(4, 0));
        pathRow.add(new JLabel("Selected path:"), BorderLayout.WEST);
        pathRow.add(selectedPathField, BorderLayout.CENTER);
        bottom.add(pathRow, BorderLayout.NORTH);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        statusLabel.setForeground(new java.awt.Color(0x80, 0x80, 0x80));
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            result = selectedPathField.getText().trim();
            dispose();
        });
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            result = null;
            dispose();
        });
        buttonRow.add(statusLabel);
        buttonRow.add(okButton);
        buttonRow.add(cancelButton);
        bottom.add(buttonRow, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        navigateTo(startPath);
    }

    private void onSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) {
            return;
        }
        int index = fileList.getSelectedIndex();
        if (index >= 0 && index < currentEntries.size()) {
            selectedPathField.setText(joinPath(currentDirField.getText().trim(), currentEntries.get(index).name));
        }
    }

    private void navigateUp() {
        String current = currentDirField.getText().trim();
        int lastSlash = current.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : current.substring(0, lastSlash);
        navigateTo(parent);
    }

    private static String joinPath(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private void navigateTo(String path) {
        String target = (path == null || path.trim().isEmpty()) ? "/" : path.trim();
        statusLabel.setText("Loading\u2026");
        statusLabel.setForeground(new java.awt.Color(0x1a, 0x73, 0xe8));

        new SwingWorker<List<RemoteRunController.RemoteFileEntry>, Void>() {
            private Exception error;

            @Override
            protected List<RemoteRunController.RemoteFileEntry> doInBackground() {
                try {
                    return controller.listDirectory(target);
                } catch (Exception ex) {
                    error = ex;
                    return java.util.Collections.emptyList();
                }
            }

            @Override
            protected void done() {
                if (error != null) {
                    statusLabel.setForeground(new java.awt.Color(0xc6, 0x28, 0x28));
                    statusLabel.setText("Failed: " + error.getMessage());
                    return;
                }
                statusLabel.setForeground(new java.awt.Color(0x80, 0x80, 0x80));
                statusLabel.setText(" ");
                currentDirField.setText(target);
                try {
                    currentEntries = get();
                } catch (Exception ignored) {
                    currentEntries = java.util.Collections.emptyList();
                }
                listModel.clear();
                for (RemoteRunController.RemoteFileEntry entry : currentEntries) {
                    listModel.addElement((entry.directory ? "\ud83d\udcc1 " : "   ") + entry.name);
                }
            }
        }.execute();
    }

    /** Shows the dialog modally and returns the chosen path, or null if
     *  cancelled. */
    String showAndGetSelectedPath() {
        setVisible(true);
        return result;
    }
}
