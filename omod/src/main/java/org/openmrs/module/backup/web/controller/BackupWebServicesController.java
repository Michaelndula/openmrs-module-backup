/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.backup.web.controller;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * This class configured as controller using annotation and mapped with the URL of
 * 'module/${rootArtifactid}/${rootArtifactid}Link.form'.
 */
@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/backup")
public class BackupWebServicesController {
	
	// OpenMRS-24
	private static final String USER = "root";
	
	private static final String PASSWORD = "openmrs";
	
	private static final String DATABASE = "openmrs";
	
	private static final String HOST = "localhost";
	
	private static final String PORT = "3306";
	
	private static final String ENCRYPTION_KEY = "5+eNyhYx5+m+57/T+YMB0As+cCDTSNVYbSB6iUMmId1VMD3uCXW+EZtVfyQfa+uF";
	
	private String userHome = System.getProperty("user.home");
	
	private String backupDirPath = userHome + "/backups";
	
	private ExecutorService executorService = Executors.newSingleThreadExecutor();
	
	private final String BACKUP_DIR_PATH = userHome + "/tmp/backupsTest";
	
	private final String PATH = userHome + "/mysqldump";
	
	@RequestMapping(method = RequestMethod.GET, value = "/exportDb")
	public String exportDatabase() {
		executorService.submit(this::runDatabaseExport);
		return "Database export started. Check the backup directory for the output...";
	}
	
	@RequestMapping(method = RequestMethod.POST, value = "/importDb")
	public String importDatabase(@RequestParam String fileName) {
		executorService.submit(() -> runDatabaseImport(fileName));
		return "Database import started...";
	}
	
	public void runDatabaseExport() {
		String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		String fileName = DATABASE + "_" + timestamp + ".sql";
		String filePath = BACKUP_DIR_PATH + File.separator + fileName;
		String encryptedFilePath = filePath + ".enc";
		
		System.out.println("------Backup directory: " + BACKUP_DIR_PATH);
		System.out.println("------Export file path: " + filePath);
		
		try {
			// Ensure backup directory exists
			Path backupDir = Paths.get(BACKUP_DIR_PATH);
			if (!Files.exists(backupDir)) {
				Files.createDirectories(backupDir); // Create directories dynamically
				System.out.println("Backup directory created: " + BACKUP_DIR_PATH);
			} else {
				System.out.println("---Proceeding with backup. Backup directory already exists: " + BACKUP_DIR_PATH);
			}
			
			// Command for MySQL dump
			String command = String.format("%s -h %s -P %s -u %s -p%s %s", PATH, HOST, PORT, USER, PASSWORD, DATABASE);
			System.out.println("-----Executing command: " + command);
			
			// Start the process
			Process process = Runtime.getRuntime().exec(command);
			
			// Process the output and error streams concurrently to avoid blocking
			Thread errorStreamThread = new Thread(() -> {
				try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
					String errorLine;
					while ((errorLine = errorReader.readLine()) != null) {
						System.err.println("Error-> " + errorLine);
					}
				}
				catch (IOException e) {
					e.printStackTrace();
				}
			});
			errorStreamThread.start();
			
			// Write the output (mysqldump content) to the SQL file
			try (InputStream inputStream = process.getInputStream();
			        OutputStream outputStream = new FileOutputStream(filePath)) {
				byte[] buffer = new byte[1024];
				int bytesRead;
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					outputStream.write(buffer, 0, bytesRead);
				}
				outputStream.flush(); // Ensure all data is written to the file
				System.out.println("----Export file written successfully to: " + filePath);
			}
			
			// Wait for the process to complete
			int processComplete = process.waitFor();
			errorStreamThread.join(); // Ensure the error thread finishes as well
			System.out.println("-----Process completed with exit code: " + processComplete);
			
			if (processComplete == 0) {
				// Encrypt the exported file
				byte[] dumpData = Files.readAllBytes(Paths.get(filePath));
				byte[] encryptedData = encrypt(dumpData);
				
				// Write the encrypted content to a new file
				Files.write(Paths.get(encryptedFilePath), encryptedData);
				
				// Clean up the original file
				Files.delete(Paths.get(filePath));
				
				System.out.println("Database exported and encrypted successfully as " + encryptedFilePath);
			} else {
				System.err.println("Database export failed!");
			}
		}
		catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	// Import and decrypt database
	private void runDatabaseImport(String fileName) {
		String filePath = backupDirPath + File.separator + fileName;
		String tempFilePath = backupDirPath + File.separator + "temp_decrypted.sql";
		
		// Ensure the backup directory exists
		Path backupDir = Paths.get(backupDirPath);
		try {
			if (!Files.exists(backupDir)) {
				Files.createDirectories(backupDir); // Create directories dynamically
				System.out.println("Backup directory created: " + backupDirPath);
			}
		}
		catch (IOException e) {
			System.err.println("Error creating backup directory: " + e.getMessage());
			return;
		}
		
		File file = new File(filePath);
		
		// Check if the encrypted file exists
		if (!file.exists() || !file.isFile()) {
			System.err.println("Error: File " + filePath + " does not exist.");
			return;
		}
		
		try {
			// Read the encrypted file
			byte[] encryptedData = Files.readAllBytes(file.toPath());
			
			// Decrypt the data
			byte[] decryptedData = decrypt(encryptedData);
			
			// Write the decrypted content to a temporary file
			Files.write(Paths.get(tempFilePath), decryptedData);
			
			// Prepare the MySQL import command (without '<')
			String command = String.format("mysql -h %s -P %s -u %s -p%s %s", HOST, PORT, USER, PASSWORD, DATABASE);
			
			// Execute the command and provide the decrypted file as input
			ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
			Process process = processBuilder.start();
			
			// Pass the SQL file as input to the process
			try (OutputStream os = process.getOutputStream(); InputStream is = new FileInputStream(tempFilePath)) {
				byte[] buffer = new byte[1024];
				int bytesRead;
				while ((bytesRead = is.read(buffer)) != -1) {
					os.write(buffer, 0, bytesRead);
				}
			}
			
			// Wait for the process to complete
			int processComplete = process.waitFor();
			
			if (processComplete == 0) {
				System.out.println("Database imported successfully from " + fileName);
			} else {
				System.err.println("Database import failed with exit code: " + processComplete);
			}
			
		}
		catch (IOException e) {
			System.err.println("Error: I/O error occurred - " + e.getMessage());
		}
		catch (InterruptedException e) {
			System.err.println("Error: Process interrupted - " + e.getMessage());
			Thread.currentThread().interrupt();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			// Clean up the temporary decrypted file
			try {
				Files.deleteIfExists(Paths.get(tempFilePath));
			}
			catch (IOException e) {
				System.err.println("Error: Could not delete temp file - " + e.getMessage());
			}
		}
	}
	
	// Encryption method using AES
	private byte[] encrypt(byte[] data) throws Exception {
		Cipher cipher = Cipher.getInstance("AES");
		SecretKey secretKey = new SecretKeySpec(ENCRYPTION_KEY.getBytes(), "AES");
		cipher.init(Cipher.ENCRYPT_MODE, secretKey);
		return cipher.doFinal(data);
	}
	
	// Decryption method using AES
	private byte[] decrypt(byte[] encryptedData) throws Exception {
		Cipher cipher = Cipher.getInstance("AES");
		SecretKey secretKey = new SecretKeySpec(ENCRYPTION_KEY.getBytes(), "AES");
		cipher.init(Cipher.DECRYPT_MODE, secretKey);
		return cipher.doFinal(encryptedData);
	}
	
}
