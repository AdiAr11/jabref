package org.jabref.gui.externalfiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.jabref.gui.preferences.GuiPreferences;
import org.jabref.gui.util.UiTaskExecutor;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.entry.event.FieldChangedEvent;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.InternalField;

import com.google.common.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinkedFileAutoRenamer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkedFileAutoRenamer.class);

    private final GuiPreferences preferences;
    private final BibDatabaseContext bibDatabaseContext;

    public LinkedFileAutoRenamer(BibDatabaseContext bibDatabaseContext, GuiPreferences preferences) {
        this.bibDatabaseContext = bibDatabaseContext;
        this.preferences = preferences;
        bibDatabaseContext.getDatabase().registerListener(this);
    }

    @Subscribe
    public void listen(FieldChangedEvent event) {
        LOGGER.debug("FieldChangedEvent triggered for field: {}", event.getField());

        if (!preferences.shouldAutoRenameLinkedFiles()) {
            LOGGER.debug("Auto rename is disabled in preferences.");
            return;
        }

        BibEntry entry = event.getBibEntry();
        Field changedField = event.getField();

        String fileNamePattern = preferences.getFilePreferences().getFileNamePattern();
        if (fileNamePattern == null || fileNamePattern.trim().isEmpty()) {
            LOGGER.debug("File name pattern is empty. Skipping renaming.");
            return;
        }

        Set<String> patternFields = FileNameParser.parseFields(fileNamePattern);
        LOGGER.debug("Parsed pattern fields: {}", patternFields);

        boolean relevantFieldChanged = false;

        // Check if the changed field is 'bibtexkey' and it's part of the pattern
        if (patternFields.contains("bibtexkey") && changedField.getName().equals(InternalField.KEY_FIELD.getName())) {
            relevantFieldChanged = true;
            LOGGER.debug("Changed field '{}' is part of the pattern.", changedField.getName());
        }

        // Check if the changed field is one of the pattern fields
        if (patternFields.contains(changedField.getName())) {
            relevantFieldChanged = true;
            LOGGER.debug("Changed field '{}' is part of the pattern.", changedField.getName());
        }

        if (!relevantFieldChanged) {
            LOGGER.debug("Changed field '{}' is not relevant for renaming.", changedField.getName());
            return; // Changed field is not relevant
        }

        handleEntryChange(entry, fileNamePattern);
    }

    private void handleEntryChange(BibEntry entry, String fileNamePattern) {
        List<Path> fileDirectories = bibDatabaseContext.getFileDirectories(preferences.getFilePreferences());

        LOGGER.debug("Handling entry change for entry: {}", entry.getCitationKey().orElse("unknown"));

        List<LinkedFile> linkedFiles = entry.getFiles();

        for (LinkedFile linkedFile : linkedFiles) {
            try {
                // Attempt to rename the file based on the pattern using LinkedFileRenamer
                boolean renamed = FileUtil.renameFile(linkedFile, entry, fileNamePattern, fileDirectories, preferences.getFilePreferences());

                if (renamed) {
                    // Notify that the linked file has changed by updating the entry's files
                    UiTaskExecutor.runInJavaFXThread(() -> {
                        entry.setFiles(linkedFiles);
                        LOGGER.debug("Updated entry files after renaming.");
                    });
                }
            } catch (IOException e) {
                LOGGER.error("Failed to rename file '{}' to pattern '{}'", linkedFile.getLink(), fileNamePattern, e);
            }
        }
    }

    public void unregisterListener() {
        bibDatabaseContext.getDatabase().unregisterListener(this);
    }
}
