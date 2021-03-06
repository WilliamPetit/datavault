package org.datavaultplatform.broker.services;

import org.datavaultplatform.common.model.User;
import org.datavaultplatform.common.model.Vault;
import org.datavaultplatform.common.model.dao.VaultDAO;
import org.datavaultplatform.common.retentionpolicy.RetentionPolicy;

import java.util.Date;
import java.util.List;

public class VaultsService {

    private VaultDAO vaultDAO;

    public List<Vault> getVaults() { return vaultDAO.list(); }

    public List<Vault> getVaults(String sort, String order) { return vaultDAO.list(sort, order); }

    public void addVault(Vault vault) {
        Date d = new Date();
        vault.setCreationTime(d);
        vaultDAO.save(vault);
    }
    
    public void updateVault(Vault vault) {
        vaultDAO.update(vault);
    }
    
    public Vault getVault(String vaultID) {
        return vaultDAO.findById(vaultID);
    }
    
    public void setVaultDAO(VaultDAO vaultDAO) {
        this.vaultDAO = vaultDAO;
    }

    public List<Vault> search(String query, String sort, String order) { return this.vaultDAO.search(query, sort, order); }

    public int count() { return vaultDAO.count(); }

    public int getRetentionPolicyCount(int status) { return vaultDAO.getRetentionPolicyCount(status); }

    public Vault checkRetentionPolicy (String vaultID) throws Exception {
        // Get the vault
        Vault vault = vaultDAO.findById(vaultID);

        // Get the right policy engine
        Class clazz = Class.forName(vault.getRetentionPolicy().getEngine());
        RetentionPolicy policy = (RetentionPolicy)clazz.newInstance();

        // Check the policy
        policy.run(vault);

        // Set the expiry date
        vault.setRetentionPolicyExpiry(policy.getReviewDate(vault));

        // Record when we checked it
        vault.setRetentionPolicyLastChecked(new Date());

        // Update and return the policy
        vaultDAO.update(vault);
        return vault;
    }
    
    // Get the specified Vault object and validate it against the current User
    public Vault getUserVault(User user, String vaultID) throws Exception {

        Vault vault = getVault(vaultID);

        if (vault == null) {
            throw new Exception("Vault '" + vaultID + "' does not exist");
        }

        Boolean userVault = false;
        if (vault.getUser().equals(user)) {
            userVault = true;
        }
        
        Boolean groupOwner = false;
        if (vault.getGroup().getOwners().contains(user)) {
            groupOwner = true;
        }
        
        Boolean adminUser = user.isAdmin();
        
        if (!userVault && !groupOwner && !adminUser) {
            throw new Exception("Access denied");
        }

        return vault;
    }
}

