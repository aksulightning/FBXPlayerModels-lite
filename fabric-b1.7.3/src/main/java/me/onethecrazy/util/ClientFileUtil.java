package me.onethecrazy.util;

import me.onethecrazy.FBXPlayerModelsMod;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientFileUtil {
    private static final ExecutorService DIALOG_THREAD = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "FBXPlayerModels-Beta-File-Dialog");
        thread.setDaemon(true);
        return thread;
    });

    private ClientFileUtil() {
    }

    public static CompletableFuture<String> modelPickerDialog() {
        CompletableFuture<String> result = new CompletableFuture<>();
        FBXPlayerModelsMod.LOGGER.info("Opening Beta-compatible FBX file picker");

        DIALOG_THREAD.execute(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> result.complete(openDialog()));
            } catch (Exception exception) {
                FBXPlayerModelsMod.LOGGER.error("Failed to open the FBX file picker", exception);
                result.complete(null);
            }
        });
        return result;
    }

    private static String openDialog() {
        File initialDirectory = FileUtil.getSkinsPath().toFile();
        JFileChooser chooser = new JFileChooser(initialDirectory);
        chooser.setDialogTitle("Choose an FBX player model");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("FBX Model (*.fbx)", "fbx"));

        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        return selected == null ? null : selected.getAbsolutePath();
    }
}
