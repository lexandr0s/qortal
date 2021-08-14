package org.qortal.storage;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.crypto.AES;
import org.qortal.repository.DataException;
import org.qortal.storage.DataFile.*;
import org.qortal.utils.ZipUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public class DataFileWriter {

    private static final Logger LOGGER = LogManager.getLogger(DataFileWriter.class);

    private Path filePath;
    private String name;
    private Service service;
    private Method method;
    private Compression compression;

    private SecretKey aesKey;
    private DataFile dataFile;

    // Intermediate paths to cleanup
    private Path workingPath;
    private Path compressedPath;
    private Path encryptedPath;

    public DataFileWriter(Path filePath, String name, Service service, Method method, Compression compression) {
        this.filePath = filePath;
        this.name = name;
        this.service = service;
        this.method = method;
        this.compression = compression;
    }

    public void save() throws IllegalStateException, IOException, DataException {
        try {
            this.preExecute();
            this.process();
            this.compress();
            this.encrypt();
            this.split();
            this.validate();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        // Enforce compression when uploading a directory
        File file = new File(this.filePath.toString());
        if (file.isDirectory() && compression == Compression.NONE) {
            throw new IllegalStateException("Unable to upload a directory without compression");
        }

        // Create temporary working directory
        this.createWorkingDirectory();
    }

    private void postExecute() throws IOException {
        this.cleanupFilesystem();
    }

    private void createWorkingDirectory() {
        // Ensure temp folder exists
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("qortal");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
        this.workingPath = tempDir;
    }

    private void process() throws DataException, IOException {
        switch (this.method) {

            case PUT:
                // Nothing to do
                break;

            case PATCH:
                this.processPatch();
                break;

            default:
                throw new IllegalStateException(String.format("Unknown method specified: %s", method.toString()));
        }
    }

    private void processPatch() throws DataException, IOException {

        // Build the existing state using past transactions
        DataFileBuilder builder = new DataFileBuilder(this.name, this.service);
        builder.build();
        Path builtPath = builder.getFinalPath();

        // Compute a diff of the latest changes on top of the previous state
        // Then use only the differences as our data payload
        DataFileCreatePatch patch = new DataFileCreatePatch(builtPath, this.filePath);
        patch.create();
        this.filePath = patch.getFinalPath();
    }

    private void compress() {
        // Compress the data if requested
        if (this.compression != Compression.NONE) {
            this.compressedPath = Paths.get(this.workingPath.toString() + File.separator + "zipped.zip");
            try {

                if (this.compression == Compression.ZIP) {
                    ZipUtils.zip(this.filePath.toString(), this.compressedPath.toString(), "data");
                }
                else {
                    throw new IllegalStateException(String.format("Unknown compression type specified: %s", compression.toString()));
                }
                // FUTURE: other compression types

                // Replace filePath pointer with the zipped file path
                // Don't delete the original file/directory, since this may be outside of our directory scope
                this.filePath = this.compressedPath;

            } catch (IOException e) {
                throw new IllegalStateException("Unable to zip directory", e);
            }
        }
    }

    private void encrypt() {
        this.encryptedPath = Paths.get(this.workingPath.toString() + File.separator + "zipped_encrypted.zip");
        try {
            // Encrypt the file with AES
            this.aesKey = AES.generateKey(256);
            AES.encryptFile("AES", this.aesKey, this.filePath.toString(), this.encryptedPath.toString());

            // Replace filePath pointer with the encrypted file path
            Files.delete(this.filePath);
            this.filePath = this.encryptedPath;

        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchPaddingException
                | BadPaddingException | IllegalBlockSizeException | IOException | InvalidKeyException e) {
            throw new IllegalStateException(String.format("Unable to encrypt file %s: %s", this.filePath, e.getMessage()));
        }
    }

    private void validate() throws IOException {
        if (this.dataFile == null) {
            throw new IOException("No file available when validating");
        }
        this.dataFile.setSecret(this.aesKey.getEncoded());

        // Validate the file
        ValidationResult validationResult = this.dataFile.isValid();
        if (validationResult != ValidationResult.OK) {
            throw new IllegalStateException(String.format("File %s failed validation: %s", this.dataFile, validationResult));
        }
        LOGGER.info("Whole file hash is valid: {}", this.dataFile.digest58());

        // Validate each chunk
        for (DataFileChunk chunk : this.dataFile.getChunks()) {
            validationResult = chunk.isValid();
            if (validationResult != ValidationResult.OK) {
                throw new IllegalStateException(String.format("Chunk %s failed validation: %s", chunk, validationResult));
            }
        }
        LOGGER.info("Chunk hashes are valid");

    }

    private void split() throws IOException {
        this.dataFile = DataFile.fromPath(this.filePath.toString());
        if (this.dataFile == null) {
            throw new IOException("No file available when trying to split");
        }

        int chunkCount = this.dataFile.split(DataFile.CHUNK_SIZE);
        if (chunkCount > 0) {
            LOGGER.info(String.format("Successfully split into %d chunk%s", chunkCount, (chunkCount == 1 ? "" : "s")));
        }
        else {
            throw new IllegalStateException("Unable to split file into chunks");
        }
    }

    private void cleanupFilesystem() throws IOException {
        // Clean up
        if (this.compressedPath != null) {
            File zippedFile = new File(this.compressedPath.toString());
            if (zippedFile.exists()) {
                zippedFile.delete();
            }
        }
        if (this.encryptedPath != null) {
            File encryptedFile = new File(this.encryptedPath.toString());
            if (encryptedFile.exists()) {
                encryptedFile.delete();
            }
        }
        if (this.workingPath != null) {
            FileUtils.deleteDirectory(new File(this.workingPath.toString()));
        }
    }


    public DataFile getDataFile() {
        return this.dataFile;
    }

}
