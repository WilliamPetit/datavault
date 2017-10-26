package org.datavaultplatform.worker.operations;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.datavaultplatform.common.io.FileCopy;
import org.datavaultplatform.common.io.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.loc.repository.bagit.Bag;
import gov.loc.repository.bagit.BagFactory;
import gov.loc.repository.bagit.BagFactory.LoadOption;
import gov.loc.repository.bagit.BagFactory.Version;
import gov.loc.repository.bagit.PreBag;
import gov.loc.repository.bagit.impl.PreBagImpl;
import gov.loc.repository.bagit.transformer.Completer;
import gov.loc.repository.bagit.utilities.FileHelper;
import gov.loc.repository.bagit.writer.impl.FileSystemWriter;

public class DatavaultPreBagImpl extends PreBagImpl {
  
  private static final Logger log = LoggerFactory.getLogger(DatavaultPreBagImpl.class);
  
  BagFactory bagFactory;
  File dir;
  List<File> tagFiles = new ArrayList<File>();
  List<String> ignoreDirs = new ArrayList<String>();

  public DatavaultPreBagImpl(BagFactory bagFactory) {
    super(bagFactory);
    // TODO Auto-generated constructor stub
  }
  
  public DatavaultPreBagImpl(PreBag preBag, BagFactory bagFactory) {
    super(bagFactory);
    this.bagFactory = bagFactory;
    this.dir = preBag.getFile();
  }
  
  @Override
  public Bag makeBagInPlace(Version version, boolean retainBaseDirectory, Completer completer) {
    log.info(MessageFormat.format("Making a bag in place at {0}", this.dir));
    File dataDir = new File(this.dir, this.bagFactory.getBagConstants(version).getDataDirectory());
    log.trace("Data directory is " + dataDir);
    try {
      //If there is no data direct
      if (! dataDir.exists()) {
        log.trace("Data directory does not exist");
        //If retainBaseDirectory
        File moveToDir = dataDir;
        if (retainBaseDirectory) {
          //Create new base directory in data directory
          moveToDir = new File(dataDir, this.dir.getName());
          //Move contents of base directory to new base directory
        }
        log.trace("Move to dir is " + moveToDir);
        for(File file : this.dir.listFiles()) {
          if (! file.equals(dataDir)) {
//            FileUtils.moveToDirectory(file, moveToDir, true);
            // Create a dummy progress object with no tracker
            Progress progress = new Progress();
            FileCopy.copyFileToDirectory(progress, file.toPath(), moveToDir, true);
            // We want to move so we got to delete the source file
            file.delete();
          }
        }
        
      } else if (! dataDir.isDirectory()) {
        throw new RuntimeException(MessageFormat.format("{0} is not a directory", dataDir));
      }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    //Copy the tags
    for(File tagFile : this.tagFiles) {
      log.trace(MessageFormat.format("Copying tag file {0} to {1}", tagFile, this.dir));
      try {
//        FileUtils.copyFileToDirectory(tagFile, this.dir);
        // Create a dummy progress object with no tracker
        Progress progress = new Progress();
        FileCopy.copyFileToDirectory(progress, tagFile.toPath(), this.dir, true);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
    
    //Create a bag (Use to be LoadOption.BY_PAYLOAD_FILES but doesn't exists anymore)
    Bag bag = this.bagFactory.createBag(this.dir, version, LoadOption.BY_FILES);
    //Complete the bag
    bag = bag.makeComplete(completer);
    //Write the bag
    return bag.write(new FileSystemWriter(this.bagFactory), this.dir);
  }
  
  @Override
  public Bag makeBagInPlace(Version version, boolean retainBaseDirectory, boolean keepEmptyDirectories, Completer completer) {
    log.debug(MessageFormat.format("Making a bag in place at {0}", this.dir));
    log.debug(MessageFormat.format("version {0}", version));
    log.debug(MessageFormat.format("bagFactory {0}", this.bagFactory));
    log.debug(MessageFormat.format("BagConstants {0}", this.bagFactory.getBagConstants(version)));
    log.debug(MessageFormat.format("DataDirectory {0}", this.bagFactory.getBagConstants(version).getDataDirectory()));
    File dataDir = new File(this.dir, this.bagFactory.getBagConstants(version).getDataDirectory());
    log.trace("Data directory is " + dataDir);
    try {
      //If there is no data direct
      if (! dataDir.exists()) {
        log.trace("Data directory does not exist");
        //If retainBaseDirectory
        File moveToDir = dataDir;
        if (retainBaseDirectory) {
          log.trace("Retaining base directory");
          //Create new base directory in data directory
          moveToDir = new File(dataDir, this.dir.getName());
          //Move contents of base directory to new base directory
        }
        log.debug(MessageFormat.format("Data directory does not exist so moving files to {0}", moveToDir));
        for(File file : FileHelper.normalizeForm(this.dir.listFiles())) {
          log.debug(MessageFormat.format("Reading file: {0}: ", file.getAbsolutePath()));
          if (! (file.equals(dataDir) || (file.isDirectory() && this.ignoreDirs.contains(file.getName())))) {
            log.trace(MessageFormat.format("Moving {0} to {1}", file, moveToDir));
            // FileUtils.moveToDirectory(file, moveToDir, true);
            // Create a dummy progress object with no tracker
            Progress progress = new Progress();
            FileCopy.copyDirectory(progress, file.toPath(), moveToDir, true);
            // We want to move so we got to delete the source file
            file.delete();
          } else {
            log.trace(MessageFormat.format("Not moving {0}", file));
          }
        }
        
      } else {
        if (! dataDir.isDirectory()) throw new RuntimeException(MessageFormat.format("{0} is not a directory", dataDir));
        //Look for additional, non-ignored files
        for(File file : FileHelper.normalizeForm(this.dir.listFiles())) {
          //If there is a directory that isn't the data dir and isn't ignored and pre v0.97 then exception
          if (file.isDirectory() 
              && (! file.equals(dataDir)) 
              && ! this.ignoreDirs.contains(file.getName())
              && (Version.V0_93 == version || Version.V0_94 == version || Version.V0_95 == version || Version.V0_96 == version)) {
            throw new RuntimeException("Found additional directories in addition to existing data directory.");
          }
        }
        
      }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    
    //Handle empty directories
    if (keepEmptyDirectories) {
      log.debug("Adding .keep files to empty directories");
      this.addKeep(dataDir);
    } else {
      log.trace("Not adding .keep files to empty directories");
    }
    
    //Copy the tags
    log.debug("Copying tag files");
    for(File tagFile : this.tagFiles) {
      log.trace(MessageFormat.format("Copying tag file {0} to {1}", tagFile, this.dir));
      try {
//        FileUtils.copyFileToDirectory(tagFile, this.dir);
        // Create a dummy progress object with no tracker
        Progress progress = new Progress();
        FileCopy.copyFileToDirectory(progress, tagFile.toPath(), this.dir, true);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
            
    //Create a bag
    log.debug(MessageFormat.format("Creating bag by payload files at {0}", this.dir));
    Bag bag = this.bagFactory.createBagByPayloadFiles(this.dir, version, this.ignoreDirs);
    //Complete the bag
    log.debug("Making complete");
    bag = bag.makeComplete(completer);
    //Write the bag
    log.debug("Writing");
    return bag.write(new FileSystemWriter(this.bagFactory), this.dir);
  }
  
  private void addKeep(File file) {
    file = FileHelper.normalizeForm(file);
    if (file.isDirectory() && ! this.ignoreDirs.contains(file.getName())) {
      //If file is empty, add .keep
      File[] children = file.listFiles();
      if (children.length == 0) {
        log.info("Adding .keep file to " + file.toString());
        try {
          FileUtils.touch(new File(file, ".keep"));
        } catch (IOException e) {
          throw new RuntimeException("Error adding .keep file to " + file.toString(), e);
        }
      } else {
        //Otherwise, recurse over children
        for(File childFile : children) {
          addKeep(childFile);
        }
      }
    }
    //Otherwise do nothing
  }
}
