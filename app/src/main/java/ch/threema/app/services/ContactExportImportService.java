/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2013-2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.services;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import ch.threema.app.R;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.domain.models.IdentityState;
import ch.threema.domain.models.IdentityType;
import ch.threema.domain.models.VerificationLevel;
import ch.threema.storage.DatabaseService;
import ch.threema.storage.factories.ContactModelFactory;
import ch.threema.storage.models.ContactModel;

/**
 * Service for exporting and importing contact lists
 */
public class ContactExportImportService {
    private static final Logger logger = LoggingUtil.getThreemaLogger("ContactExportImportService");
    
    private final Context context;
    private final ContactService contactService;
    private final DatabaseService databaseService;
    
    public ContactExportImportService(@NonNull Context context, @NonNull ContactService contactService, @NonNull DatabaseService databaseService) {
        this.context = context;
        this.contactService = contactService;
        this.databaseService = databaseService;
    }
    
    /**
     * Export all contacts to a JSON file
     * @return The exported file path on success, null on failure
     */
    @Nullable
    public String exportContacts() {
        try {
            List<ContactModel> contacts = contactService.getAll();
            if (contacts.isEmpty()) {
                return null;
            }
            
            JSONArray contactsArray = new JSONArray();
            
            for (ContactModel contact : contacts) {
                // Skip special contacts (gateway contacts like *THREEMA, *SUPPORT, etc.)
                if (contact.getIdentity().startsWith("*")) {
                    continue;
                }
                
                JSONObject contactObj = new JSONObject();
                contactObj.put("identity", contact.getIdentity());
                contactObj.put("publicKey", android.util.Base64.encodeToString(contact.getPublicKey(), android.util.Base64.DEFAULT));
                contactObj.put("firstName", contact.getFirstName() != null ? contact.getFirstName() : "");
                contactObj.put("lastName", contact.getLastName() != null ? contact.getLastName() : "");
                contactObj.put("publicNickName", contact.getPublicNickName() != null ? contact.getPublicNickName() : "");
                contactObj.put("verificationLevel", contact.verificationLevel.ordinal());
                contactObj.put("identityType", contact.getIdentityType().ordinal());
                contactObj.put("isWorkVerified", contact.isWorkVerified());
                contactObj.put("jobTitle", contact.getJobTitle() != null ? contact.getJobTitle() : "");
                contactObj.put("department", contact.getDepartment() != null ? contact.getDepartment() : "");
                contactObj.put("acquaintanceLevel", contact.getAcquaintanceLevel().ordinal());
                contactObj.put("state", contact.getState().toString());
                contactObj.put("featureMask", contact.getFeatureMask());
                contactObj.put("readReceipts", contact.getReadReceipts());
                contactObj.put("typingIndicators", contact.getTypingIndicators());
                contactObj.put("isArchived", contact.isArchived());
                
                if (contact.getDateCreated() != null) {
                    contactObj.put("dateCreated", contact.getDateCreated().getTime());
                }
                
                contactsArray.put(contactObj);
            }
            
            JSONObject exportData = new JSONObject();
            exportData.put("version", 1);
            exportData.put("exportDate", System.currentTimeMillis());
            exportData.put("contacts", contactsArray);
            
            // Create filename with timestamp
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String filename = context.getString(R.string.contact_export_filename, timestamp);
            
            // Save to Downloads directory
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File exportFile = new File(downloadsDir, filename);
            
            FileWriter writer = new FileWriter(exportFile);
            writer.write(exportData.toString(2)); // Pretty print with 2-space indentation
            writer.close();
            
            logger.info("Contacts exported successfully to: " + exportFile.getAbsolutePath());
            return exportFile.getAbsolutePath();
            
        } catch (JSONException | IOException e) {
            logger.error("Failed to export contacts", e);
            return null;
        }
    }
    
    /**
     * Import contacts from a JSON file
     * @param filePath Path to the JSON file to import
     * @return ImportResult containing the number of successful and failed imports
     */
    @NonNull
    public ImportResult importContacts(@NonNull String filePath) {
        ImportResult result = new ImportResult();
        
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.canRead()) {
                result.errorMessage = "File not found or not readable";
                return result;
            }
            
            Scanner scanner = new Scanner(new FileInputStream(file));
            StringBuilder jsonContent = new StringBuilder();
            while (scanner.hasNextLine()) {
                jsonContent.append(scanner.nextLine());
            }
            scanner.close();
            
            JSONObject importData = new JSONObject(jsonContent.toString());
            
            // Check version compatibility
            int version = importData.optInt("version", 0);
            if (version != 1) {
                result.errorMessage = "Unsupported file version: " + version;
                return result;
            }
            
            JSONArray contactsArray = importData.getJSONArray("contacts");
            result.totalContacts = contactsArray.length();
            
            List<String> skippedContacts = new ArrayList<>();
            List<String> errorMessages = new ArrayList<>();
            
            for (int i = 0; i < contactsArray.length(); i++) {
                try {
                    JSONObject contactObj = contactsArray.getJSONObject(i);
                    String identity = contactObj.getString("identity");
                    
                    // Check if contact already exists
                    ContactModel existingContact = contactService.getByIdentity(identity);
                    if (existingContact != null) {
                        // Update existing contact
                        updateContactFromJson(existingContact, contactObj);
                        result.successCount++;
                        logger.info("Updated existing contact: " + identity);
                    } else {
                        // Create new contact for device migration scenarios
                        ContactModel newContact = createContactFromJson(contactObj);
                        if (newContact != null) {
                            result.successCount++;
                            logger.info("Created new contact: " + identity);
                        } else {
                            result.failedCount++;
                            skippedContacts.add(identity);
                            logger.warn("Failed to create new contact: " + identity);
                        }
                    }
                } catch (Exception e) {
                    result.failedCount++;
                    String errorMsg = "Contact " + (i + 1) + ": " + e.getMessage();
                    errorMessages.add(errorMsg);
                    logger.error("Failed to import contact at index " + i, e);
                }
            }
            
            // Build detailed result message
            if (result.failedCount > 0) {
                StringBuilder details = new StringBuilder();
                if (!skippedContacts.isEmpty()) {
                    details.append("Failed to create ").append(skippedContacts.size())
                           .append(" contacts (missing public key or invalid data). ");
                }
                if (!errorMessages.isEmpty()) {
                    details.append("Errors: ").append(String.join("; ", errorMessages));
                }
                result.errorMessage = details.toString();
            }
            
        } catch (IOException | JSONException e) {
            result.errorMessage = "Failed to read or parse file: " + e.getMessage();
            logger.error("Failed to import contacts", e);
        }
        
        return result;
    }
    
    private void updateContactFromJson(@NonNull ContactModel contact, @NonNull JSONObject contactObj) throws JSONException {
        boolean wasModified = false;
        
        // Update only safe fields (not identity or public key)
        if (contactObj.has("firstName")) {
            String firstName = contactObj.getString("firstName");
            String lastName = contactObj.optString("lastName", "");
            contact.setName(firstName, lastName);
            wasModified = true;
        }
        
        if (contactObj.has("publicNickName")) {
            String newNickName = contactObj.getString("publicNickName");
            if (!newNickName.equals(contact.getPublicNickName())) {
                contact.setPublicNickName(newNickName);
                wasModified = true;
            }
        }
        
        if (contactObj.has("jobTitle")) {
            String newJobTitle = contactObj.getString("jobTitle");
            if (!newJobTitle.equals(contact.getJobTitle())) {
                contact.setJobTitle(newJobTitle);
                wasModified = true;
            }
        }
        
        if (contactObj.has("department")) {
            String newDepartment = contactObj.getString("department");
            if (!newDepartment.equals(contact.getDepartment())) {
                contact.setDepartment(newDepartment);
                wasModified = true;
            }
        }
        
        if (contactObj.has("readReceipts")) {
            int newReadReceipts = contactObj.getInt("readReceipts");
            if (newReadReceipts != contact.getReadReceipts()) {
                contact.setReadReceipts(newReadReceipts);
                wasModified = true;
            }
        }
        
        if (contactObj.has("typingIndicators")) {
            int newTypingIndicators = contactObj.getInt("typingIndicators");
            if (newTypingIndicators != contact.getTypingIndicators()) {
                contact.setTypingIndicators(newTypingIndicators);
                wasModified = true;
            }
        }
        
        if (contactObj.has("isArchived")) {
            boolean newArchived = contactObj.getBoolean("isArchived");
            if (newArchived != contact.isArchived()) {
                contact.setArchived(newArchived);
                wasModified = true;
            }
        }
        
        // Ensure imported/updated contacts are always visible by setting acquaintance level to DIRECT
        if (contact.getAcquaintanceLevel() != ContactModel.AcquaintanceLevel.DIRECT) {
            logger.info("Updating contact " + contact.getIdentity() + " acquaintanceLevel from " + contact.getAcquaintanceLevel() + " to DIRECT (imported contacts should be visible)");
            contact.setAcquaintanceLevel(ContactModel.AcquaintanceLevel.DIRECT);
            wasModified = true;
        }
        
        // Ensure imported/updated contacts are always visible by setting acquaintance level to DIRECT
        if (contact.getAcquaintanceLevel() != ContactModel.AcquaintanceLevel.DIRECT) {
            logger.info("Updating contact " + contact.getIdentity() + " acquaintanceLevel from " + contact.getAcquaintanceLevel() + " to DIRECT (imported contacts should be visible)");
            contact.setAcquaintanceLevel(ContactModel.AcquaintanceLevel.DIRECT);
            wasModified = true;
        }
        
        // Save the updated contact to persist changes
        if (wasModified) {
            contactService.save(contact);
            logger.info("Updated contact: " + contact.getIdentity());
        }
    }
    
    /**
     * Create a new contact from JSON data
     * @param contactObj JSON object containing contact data
     * @return Created ContactModel or null if creation failed
     */
    @Nullable
    private ContactModel createContactFromJson(@NonNull JSONObject contactObj) {
        try {
            String identity = contactObj.getString("identity");
            String publicKeyBase64 = contactObj.getString("publicKey");
            
            // Decode the public key
            byte[] publicKey = android.util.Base64.decode(publicKeyBase64, android.util.Base64.DEFAULT);
            
            // Create new contact model using proper factory method
            ContactModel newContact = ContactModel.create(identity, publicKey);
            
            // Set basic info
            if (contactObj.has("firstName") && contactObj.has("lastName")) {
                String firstName = contactObj.getString("firstName");
                String lastName = contactObj.getString("lastName");
                newContact.setName(firstName, lastName);
            }
            
            if (contactObj.has("publicNickName")) {
                newContact.setPublicNickName(contactObj.getString("publicNickName"));
            }
            
            if (contactObj.has("jobTitle")) {
                newContact.setJobTitle(contactObj.getString("jobTitle"));
            }
            
            if (contactObj.has("department")) {
                newContact.setDepartment(contactObj.getString("department"));
            }
            
            // Set verification level
            if (contactObj.has("verificationLevel")) {
                int verificationLevel = contactObj.getInt("verificationLevel");
                VerificationLevel[] levels = VerificationLevel.values();
                if (verificationLevel >= 0 && verificationLevel < levels.length) {
                    newContact.verificationLevel = levels[verificationLevel];
                }
            }
            
            // Set identity type
            if (contactObj.has("identityType")) {
                int identityType = contactObj.getInt("identityType");
                IdentityType[] types = IdentityType.values();
                if (identityType >= 0 && identityType < types.length) {
                    newContact.setIdentityType(types[identityType]);
                }
            }
            
            // Set acquaintance level - always use DIRECT for imported contacts so they are visible
            // (imported contacts should always be displayed since user explicitly imported them)
            logger.info("Setting contact " + identity + " acquaintanceLevel to DIRECT (imported contacts should be visible)");
            newContact.setAcquaintanceLevel(ContactModel.AcquaintanceLevel.DIRECT);
            
            // Set other properties
            if (contactObj.has("isWorkVerified")) {
                newContact.setIsWork(contactObj.getBoolean("isWorkVerified"));
            }
            
            if (contactObj.has("featureMask")) {
                newContact.setFeatureMask(contactObj.getLong("featureMask"));
            }
            
            if (contactObj.has("readReceipts")) {
                newContact.setReadReceipts(contactObj.getInt("readReceipts"));
            }
            
            if (contactObj.has("typingIndicators")) {
                newContact.setTypingIndicators(contactObj.getInt("typingIndicators"));
            }
            
            if (contactObj.has("isArchived")) {
                newContact.setArchived(contactObj.getBoolean("isArchived"));
            }
            
            // Set creation date
            if (contactObj.has("dateCreated")) {
                long dateCreated = contactObj.getLong("dateCreated");
                newContact.setDateCreated(new Date(dateCreated));
            } else {
                newContact.setDateCreated(new Date());
            }
            
            // Set state
            if (contactObj.has("state")) {
                String stateStr = contactObj.getString("state");
                try {
                    IdentityState state = IdentityState.valueOf(stateStr);
                    newContact.setState(state);
                } catch (IllegalArgumentException e) {
                    newContact.setState(IdentityState.ACTIVE);
                }
            } else {
                newContact.setState(IdentityState.ACTIVE);
            }
            
            // Create the contact using ContactModelFactory
            ContactModelFactory contactModelFactory = databaseService.getContactModelFactory();
            boolean success = contactModelFactory.createOrUpdate(newContact);
            
            if (success) {
                logger.info("Successfully created new contact: " + identity);
                return newContact;
            } else {
                logger.error("Failed to create contact in database: " + identity);
                return null;
            }
            
        } catch (Exception e) {
            logger.error("Failed to create contact from JSON", e);
            return null;
        }
    }
    
    /**
     * Result of an import operation
     */
    public static class ImportResult {
        public int totalContacts = 0;
        public int successCount = 0;
        public int failedCount = 0;
        public String errorMessage = null;
        
        public boolean isSuccess() {
            return errorMessage == null && successCount > 0;
        }
        
        public boolean isPartialSuccess() {
            return errorMessage == null && successCount > 0 && failedCount > 0;
        }
    }
}
