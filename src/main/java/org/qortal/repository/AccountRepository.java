package org.qortal.repository;

import java.util.List;

import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.QortFromQoraData;
import org.qortal.data.account.RewardShareData;

public interface AccountRepository {

	// General account

	/** Returns all general information about account, e.g. public key, last reference, default group ID. */
	public AccountData getAccount(String address) throws DataException;

	/** Returns accounts with <b>any</b> bit set in given mask. */
	public List<AccountData> getFlaggedAccounts(int mask) throws DataException;

	/** Returns account's last reference or null if not set or account not found. */
	public byte[] getLastReference(String address) throws DataException;

	/** Returns account's default groupID or null if account not found. */
	public Integer getDefaultGroupId(String address) throws DataException;

	/** Returns account's flags or null if account not found. */
	public Integer getFlags(String address) throws DataException;

	/** Returns account's level or null if account not found. */
	public Integer getLevel(String address) throws DataException;

	/** Returns whether account exists. */
	public boolean accountExists(String address) throws DataException;

	/**
	 * Ensures at least minimal account info in repository.
	 * <p>
	 * Saves account address, and public key if present.
	 */
	public void ensureAccount(AccountData accountData) throws DataException;

	/**
	 * Saves account's last reference, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like default group ID.
	 */
	public void setLastReference(AccountData accountData) throws DataException;

	/**
	 * Saves account's default groupID, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference.
	 */
	public void setDefaultGroupId(AccountData accountData) throws DataException;

	/**
	 * Saves account's flags, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setFlags(AccountData accountData) throws DataException;

	/**
	 * Saves account's level, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setLevel(AccountData accountData) throws DataException;

	/**
	 * Saves account's blocks-minted adjustment, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setBlocksMintedAdjustment(AccountData accountData) throws DataException;

	/**
	 * Saves account's minted block count and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setMintedBlockCount(AccountData accountData) throws DataException;

	/** Modifies account's minted block count only.
	 * <p>
	 * @return 2 if minted block count updated, 1 if block count set to delta, 0 if address not found.
	 */
	public int modifyMintedBlockCount(String address, int delta) throws DataException;

	/** Delete account from repository. */
	public void delete(String address) throws DataException;

	/** Generic opportunistic tidy. */
	public void tidy() throws DataException;

	// Account balances

	public AccountBalanceData getBalance(String address, long assetId) throws DataException;

	public enum BalanceOrdering {
		ASSET_BALANCE_ACCOUNT,
		ACCOUNT_ASSET,
		ASSET_ACCOUNT
	}

	public List<AccountBalanceData> getAssetBalances(long assetId, Boolean excludeZero) throws DataException;

	public List<AccountBalanceData> getAssetBalances(List<String> addresses, List<Long> assetIds, BalanceOrdering balanceOrdering, Boolean excludeZero, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public void modifyAssetBalance(String address, long assetId, long deltaBalance) throws DataException;

	public void save(AccountBalanceData accountBalanceData) throws DataException;

	public void delete(String address, long assetId) throws DataException;

	// Reward-shares

	public RewardShareData getRewardShare(byte[] mintingAccountPublicKey, String recipientAccount) throws DataException;

	public RewardShareData getRewardShare(byte[] rewardSharePublicKey) throws DataException;

	public boolean isRewardSharePublicKey(byte[] publicKey) throws DataException;

	/** Returns number of active reward-shares involving passed public key as the minting account only. */
	public int countRewardShares(byte[] mintingAccountPublicKey) throws DataException;

	public List<RewardShareData> getRewardShares() throws DataException;

	public List<RewardShareData> findRewardShares(List<String> mintingAccounts, List<String> recipientAccounts, List<String> involvedAddresses, Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns index in list of reward-shares (sorted by reward-share public key).
	 * <p>
	 * @return index (from 0) or null if publicKey not found in repository.
	 */
	public Integer getRewardShareIndex(byte[] rewardSharePublicKey) throws DataException;

	/**
	 * Returns reward-share data using index into list of reward-shares (sorted by reward-share public key).
	 */
	public RewardShareData getRewardShareByIndex(int index) throws DataException;

	public boolean rewardShareExists(byte[] rewardSharePublicKey) throws DataException;

	public void save(RewardShareData rewardShareData) throws DataException;

	/** Delete reward-share from repository using passed minting account's public key and recipient's address. */
	public void delete(byte[] mintingAccountPublickey, String recipient) throws DataException;

	// Minting accounts used by BlockMinter, potentially includes reward-shares

	public List<MintingAccountData> getMintingAccounts() throws DataException;

	public void save(MintingAccountData mintingAccountData) throws DataException;

	/** Delete minting account info, used by BlockMinter, from repository using passed private key. */
	public int delete(byte[] mintingAccountPrivateKey) throws DataException;

	// Managing QORT from legacy QORA

	/**
	 * Returns balance data for accounts with legacy QORA asset that are eligible
	 * for more block reward (block processing) or for block reward removal (block orphaning).
	 * <p>
	 * For block processing, accounts that have already received their final QORT reward for owning
	 * legacy QORA are omitted from the results. <tt>blockHeight</tt> should be <tt>null</tt>.
	 * <p>
	 * For block orphaning, accounts that did not receive a QORT reward at <tt>blockHeight</tt>
	 * are omitted from the results.
	 * 
	 * @param blockHeight QORT reward must have be present at this height (for orphaning only)
	 * @throws DataException
	 */
	public List<AccountBalanceData> getEligibleLegacyQoraHolders(Integer blockHeight) throws DataException;

	public QortFromQoraData getQortFromQoraInfo(String address) throws DataException;

	public void save(QortFromQoraData qortFromQoraData) throws DataException;

	public int deleteQortFromQoraInfo(String address) throws DataException;

}
