package org.datavaultplatform.worker.tasks;

import java.util.Map;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.io.FileUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.datavaultplatform.common.task.Context;
import org.datavaultplatform.common.task.Task;
import org.datavaultplatform.common.event.Error;
import org.datavaultplatform.common.event.InitStates;
import org.datavaultplatform.common.event.UpdateProgress;
import org.datavaultplatform.common.event.deposit.Start;
import org.datavaultplatform.common.event.deposit.ComputedSize;
import org.datavaultplatform.common.event.deposit.TransferComplete;
import org.datavaultplatform.common.event.deposit.PackageComplete;
import org.datavaultplatform.common.event.deposit.Complete;
import org.datavaultplatform.common.event.deposit.ComputedDigest;
import org.datavaultplatform.common.io.Progress;
import org.datavaultplatform.common.storage.*;
import org.datavaultplatform.worker.WorkerInstance;
import org.datavaultplatform.worker.operations.*;
import org.datavaultplatform.worker.queue.EventSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class that extends Task which is used to handle Deposits to the vault
 */
public class Deposit extends Task {

    private static final Logger logger = LoggerFactory.getLogger(Deposit.class);
    
    EventSender eventStream;
    HashMap<String, UserStore> userStores;

    // todo: I'm sure these two maps could be combined in some way.

    // Maps the model ArchiveStore Id to the storage equivelant
    HashMap<String, ArchiveStore> archiveStores = new HashMap<>();

    // Maps the model ArchiveStore Id to the generated Archive Id
    HashMap<String, String> archiveIds = new HashMap<>();

    String depositId;
    String bagID;
    String userID;
    
    /* (non-Javadoc)
     * @see org.datavaultplatform.common.task.Task#performAction(org.datavaultplatform.common.task.Context)
     */
    @Override
    public void performAction(Context context) {
        
        eventStream = (EventSender)context.getEventStream();
        
        logger.info("Deposit job - performAction()");
        
        Map<String, String> properties = getProperties();
        depositId = properties.get("depositId");
        bagID = properties.get("bagId");
        userID = properties.get("userId");
        
        if (this.isRedeliver()) {
            eventStream.send(new Error(jobID, depositId, "Deposit stopped: the message had been redelivered, please investigate")
                .withUserId(userID));
            return;
        }
        
        // Deposit and Vault metadata to be stored in the bag
        // TODO: is there a better way to pass this to the worker?
        String depositMetadata = properties.get("depositMetadata");
        String vaultMetadata = properties.get("vaultMetadata");
        String externalMetadata = properties.get("externalMetadata");
        
        ArrayList<String> states = new ArrayList<>();
        states.add("Calculating size");     // 0
        states.add("Transferring");         // 1
        states.add("Packaging");            // 2
        states.add("Storing in archive");   // 3
        states.add("Verifying");            // 4
        states.add("Complete");             // 5
        eventStream.send(new InitStates(jobID, depositId, states)
            .withUserId(userID));
        
        eventStream.send(new Start(jobID, depositId)
            .withUserId(userID)
            .withNextState(0));
        
        logger.info("bagID: " + bagID);
        
        userStores = new HashMap<>();
        
        for (String storageID : userFileStoreClasses.keySet()) {
            
            String storageClass = userFileStoreClasses.get(storageID);
            Map<String, String> storageProperties = userFileStoreProperties.get(storageID);
            
            // Connect to the user storage devices
            try {
                Class<?> clazz = Class.forName(storageClass);
                Constructor<?> constructor = clazz.getConstructor(String.class, Map.class);
                Object instance = constructor.newInstance(storageClass, storageProperties);                
                Device userFs = (Device)instance;
                UserStore userStore = (UserStore)userFs;
                userStores.put(storageID, userStore);
                logger.info("Connected to user store: " + storageID + ", class: " + storageClass);
            } catch (Exception e) {
                String msg = "Deposit failed: could not access user filesystem";
                logger.error(msg, e);
                eventStream.send(new Error(jobID, depositId, msg)
                    .withUserId(userID));
                return;
            }
        }
        
        for (String path : fileStorePaths) {
            System.out.println("fileStorePath: " + path);
        }
        for (String path : fileUploadPaths) {
            System.out.println("fileUploadPath: " + path);
        }

        // Connect to the archive storage(s). Look out! There are two classes called archiveStore.
        for (org.datavaultplatform.common.model.ArchiveStore archiveFileStore : archiveFileStores ) {
            try {
                Class<?> clazz = Class.forName(archiveFileStore.getStorageClass());
                Constructor<?> constructor = clazz.getConstructor(String.class, Map.class);
                Object instance = constructor.newInstance(archiveFileStore.getStorageClass(), archiveFileStore.getProperties());

                archiveStores.put(archiveFileStore.getID(), (ArchiveStore)instance);

            } catch (Exception e) {
                String msg = "Deposit failed: could not access archive filesystem : " + archiveFileStore.getStorageClass();
                logger.error(msg, e);
                eventStream.send(new Error(jobID, depositId, msg)
                        .withUserId(userID));
                return;
            }
        }
        
        // Calculate the total deposit size of selected files
        long depositTotalSize = 0;
        for (String filePath: fileStorePaths) {
        
            String storageID = filePath.substring(0, filePath.indexOf('/'));
            String storagePath = filePath.substring(filePath.indexOf('/')+1);
            
            try {
                UserStore userStore = userStores.get(storageID);
                depositTotalSize += userStore.getSize(storagePath);
            } catch (Exception e) {
                String msg = "Deposit failed: could not access user filesystem";
                logger.error(msg, e);
                eventStream.send(new Error(jobID, depositId, msg)
                    .withUserId(userID));
                return;
            }
        }
        
        // Add size of any user uploads
        try {
            for (String path : fileUploadPaths) {
                depositTotalSize += getUserUploadsSize(context.getTempDir(), userID, path);
            }
        } catch (Exception e) {
            String msg = "Deposit failed: could not access user uploads";
            logger.error(msg, e);
            eventStream.send(new Error(jobID, depositId, msg)
                .withUserId(userID));
            return;
        }
        
        // Store the calculated deposit size
        eventStream.send(new ComputedSize(jobID, depositId, depositTotalSize)
            .withUserId(userID));
        
        // Create a new directory based on the broker-generated UUID
        Path bagPath = context.getTempDir().resolve(bagID);
        File bagDir = bagPath.toFile();
        bagDir.mkdir();
        
        Long depositIndex = 0L;
        
        for (String filePath: fileStorePaths) {
        
            String storageID = filePath.substring(0, filePath.indexOf('/'));
            String storagePath = filePath.substring(filePath.indexOf('/')+1);
            
            logger.info("Deposit file: " + filePath);
            logger.info("Deposit storageID: " + storageID);
            logger.info("Deposit storagePath: " + storagePath);
            
            UserStore userStore = userStores.get(storageID);
            
            Path depositPath = bagPath;
            // If there are multiple deposits then create a sub-directory for each one
            if (fileStorePaths.size() > 1) {
                depositIndex += 1;
                depositPath = bagPath.resolve(depositIndex.toString());
                depositPath.toFile().mkdir();
            }
            
            try {
                if (userStore.exists(storagePath)) {

                    // Copy the target file to the bag directory
                    eventStream.send(new UpdateProgress(jobID, depositId)
                        .withUserId(userID)
                        .withNextState(1));

                    logger.info("Copying target to bag directory ...");
                    copyFromUserStorage(userStore, storagePath, depositPath);
                    
                } else {
                    logger.error("File does not exist.");
                    eventStream.send(new Error(jobID, depositId, "Deposit failed: file not found")
                        .withUserId(userID));
                }
            } catch (Exception e) {
                String msg = "Deposit failed: " + e.getMessage();
                logger.error(msg, e);
                eventStream.send(new Error(jobID, depositId, msg)
                    .withUserId(userID));
            }
        }
        
        try {

            // Add any directly uploaded files (direct move from temp dir)            
            for (String path : fileUploadPaths) {
                moveFromUserUploads(context.getTempDir(), bagPath, userID, path);
            }
            
            // Bag the directory in-place
            eventStream.send(new TransferComplete(jobID, depositId)
                .withUserId(userID)
                .withNextState(2));

            logger.info("Creating bag ...");
            Packager.createBag(bagDir);

            // Identify the deposit file types
            logger.info("Identifying file types ...");
            Path bagDataPath = bagDir.toPath().resolve("data");
            HashMap<String, String> fileTypes = Identifier.detectDirectory(bagDataPath);
            ObjectMapper mapper = new ObjectMapper();
            String fileTypeMetadata = mapper.writeValueAsString(fileTypes);

            // Add vault/deposit/type metadata to the bag
            Packager.addMetadata(bagDir, depositMetadata, vaultMetadata, fileTypeMetadata, externalMetadata);

            // Tar the bag directory
            logger.info("Creating tar file ...");
            String tarFileName = bagID + ".tar";
            Path tarPath = context.getTempDir().resolve(tarFileName);
            File tarFile = tarPath.toFile();
            Tar.createTar(bagDir, tarFile);
            String tarHash = Verify.getDigest(tarFile);
            String tarHashAlgorithm = Verify.getAlgorithm();

            eventStream.send(new PackageComplete(jobID, depositId)
                .withUserId(userID)
                .withNextState(3));

            long archiveSize = tarFile.length();
            logger.info("Tar file: " + archiveSize + " bytes");
            logger.info("Checksum algorithm: " + tarHashAlgorithm);
            logger.info("Checksum: " + tarHash);

            eventStream.send(new ComputedDigest(jobID, depositId, tarHash, tarHashAlgorithm)
                .withUserId(userID));

            // Create the meta directory for the bag information
            Path metaPath = context.getMetaDir().resolve(bagID);
            File metaDir = metaPath.toFile();
            metaDir.mkdir();

            // Copy bag meta files to the meta directory
            logger.info("Copying meta files ...");
            Packager.extractMetadata(bagDir, metaDir);

            // Copy the resulting tar file to the archive area
            logger.info("Copying tar file to archive ...");
            copyToArchiveStorage(tarFile);

            // Cleanup
            logger.info("Cleaning up ...");
            FileUtils.deleteDirectory(bagDir);

            eventStream.send(new UpdateProgress(jobID, depositId)
                .withUserId(userID)
                .withNextState(4));

            logger.info("Verifying archive package ...");
            verifyArchive(context, tarFile, tarHash);

            logger.info("Deposit complete");

            eventStream.send(new Complete(jobID, depositId, archiveIds, archiveSize)
                .withUserId(userID)
                .withNextState(5));
        } catch (Exception e) {
            String msg = "Deposit failed: " + e.getMessage();
            logger.error(msg, e);
            eventStream.send(new Error(jobID, depositId, msg)
                .withUserId(userID));
        }
        
        // TODO: Disconnect from user and archive storage system?
    }
    
    /**
     * @param userStore
     * @param filePath
     * @param bagPath
     * @throws Exception
     */
    private void copyFromUserStorage(UserStore userStore, String filePath, Path bagPath) throws Exception {

        String fileName = userStore.getName(filePath);
        File outputFile = bagPath.resolve(fileName).toFile();
        
        // Compute bytes to copy
        long expectedBytes = userStore.getSize(filePath);
        
        // Display progress bar
        eventStream.send(new UpdateProgress(jobID, depositId, 0, expectedBytes, "Starting transfer ...")
            .withUserId(userID));
        
        logger.info("Size: " + expectedBytes + " bytes (" +  FileUtils.byteCountToDisplaySize(expectedBytes) + ")");
        
        // Progress tracking (threaded)
        Progress progress = new Progress();
        ProgressTracker tracker = new ProgressTracker(progress, jobID, depositId, expectedBytes, eventStream);
        Thread trackerThread = new Thread(tracker);
        trackerThread.start();
        
        try {
            // Ask the driver to copy files to our working directory
            ((Device)userStore).retrieve(filePath, outputFile, progress);
        } finally {
            // Stop the tracking thread
            tracker.stop();
            trackerThread.join();
        }
    }
    
    /**
     * @param tempPath
     * @param userID
     * @param uploadPath
     * @return
     * @throws Exception
     */
    private long getUserUploadsSize(Path tempPath, String userID, String uploadPath) throws Exception {
        // TODO: this is a bit of a hack to escape the per-worker temp directory
        File uploadDir = tempPath.getParent().resolve("uploads").resolve(userID).resolve(uploadPath).toFile();
        if (uploadDir.exists()) {
            logger.info("Calculating size of user uploads directory");
            return FileUtils.sizeOfDirectory(uploadDir);
        }
        return 0;
    }
    
    /**
     * @param tempPath
     * @param bagPath
     * @param userID
     * @param uploadPath
     * @throws Exception
     */
    private void moveFromUserUploads(Path tempPath, Path bagPath, String userID, String uploadPath) throws Exception {
        
        File outputFile = bagPath.resolve("uploads").toFile();
        
        // TODO: this is a bit of a hack to escape the per-worker temp directory
        File uploadDir = tempPath.getParent().resolve("uploads").resolve(userID).resolve(uploadPath).toFile();
        if (uploadDir.exists()) {
            logger.info("Moving user uploads to bag directory");
            FileUtils.moveDirectory(uploadDir, outputFile);
        }
    }

    /**
     * @param tarFile
     * @throws Exception
     */
    private void copyToArchiveStorage(File tarFile) throws Exception {

        for (String archiveStoreId : archiveStores.keySet() ) {
            ArchiveStore archiveStore = archiveStores.get(archiveStoreId);

            // Progress tracking (threaded)
            Progress progress = new Progress();
            ProgressTracker tracker = new ProgressTracker(progress, jobID, depositId, tarFile.length(), eventStream);
            Thread trackerThread = new Thread(tracker);
            trackerThread.start();

            String archiveId;

            try {
                archiveId = ((Device) archiveStore).store("/", tarFile, progress);
            } finally {
                // Stop the tracking thread
                tracker.stop();
                trackerThread.join();
            }

            logger.info("Copied: " + progress.dirCount + " directories, " + progress.fileCount + " files, " + progress.byteCount + " bytes");

            archiveIds.put(archiveStoreId, archiveId);
        }
    }

    /**
     * @param context
     * @param tarFile
     * @param tarHash
     * @throws Exception
     */
    private void verifyArchive(Context context, File tarFile, String tarHash) throws Exception {

        boolean alreadyVerified = false;

        for (String archiveStoreId : archiveStores.keySet() ) {
            ArchiveStore archiveStore = archiveStores.get(archiveStoreId);
            String archiveId = archiveIds.get(archiveStoreId);

            logger.info("Verification method: " + archiveStore.getVerifyMethod());

            // Get the tar file

            if ((archiveStore.getVerifyMethod() == Verify.Method.LOCAL_ONLY) && (!alreadyVerified)){

                // Verify the contents of the temporary file
                verifyTarFile(context.getTempDir(), tarFile, null);

            } else if (archiveStore.getVerifyMethod() == Verify.Method.COPY_BACK) {

                alreadyVerified = true;

                // Delete the existing temporary file
                tarFile.delete();

                // Copy file back from the archive storage
                copyBackFromArchive(archiveStore, archiveId, tarFile);

                // Verify the contents
                verifyTarFile(context.getTempDir(), tarFile, tarHash);
            }
        }
    }

    /**
     * Not sure what this does yet the comment below suggests it copies an archive to the tmp dir
     * why are deposit would do this I'm not sure
     * 
     * @param archiveStore
     * @param archiveId
     * @param tarFile
     * @throws Exception
     */
    private void copyBackFromArchive(ArchiveStore archiveStore, String archiveId, File tarFile) throws Exception {

        // Ask the driver to copy files to the temp directory
        Progress progress = new Progress();
        ((Device)archiveStore).retrieve(archiveId, tarFile, progress);
        logger.info("Copied: " + progress.dirCount + " directories, " + progress.fileCount + " files, " + progress.byteCount + " bytes");
    }
    
    /**
     * Compare the SHA hash of the passed in tar file and the passed in original hash.
     * 
     * First compare the tar file then the bag then clean up
     * 
     * If the verification fails at either check throw an exception (maybe we could throw separate exceptions here)
     * @param tempPath Path to the temp storage location
     * @param tarFile File 
     * @param origTarHash String representing the orig hash
     * @throws Exception
     */
    private void verifyTarFile(Path tempPath, File tarFile, String origTarHash) throws Exception {

        if (origTarHash != null) {
            // Compare the SHA hash
            String tarHash = Verify.getDigest(tarFile);
            logger.info("Checksum: " + tarHash);
            if (!tarHash.equals(origTarHash)) {
                throw new Exception("checksum failed: " + tarHash + " != " + origTarHash);
            }
        }
        
        // Decompress to the temporary directory
        File bagDir = Tar.unTar(tarFile, tempPath);
        
        // Validate the bagit directory
        if (!Packager.validateBag(bagDir)) {
            throw new Exception("Bag is invalid");
        } else {
            logger.info("Bag is valid");
        }
        
        // Cleanup
        logger.info("Cleaning up ...");
        FileUtils.deleteDirectory(bagDir);
        tarFile.delete();
    }
}