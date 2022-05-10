package org.qortal.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats;
import com.rust.litewalletjni.LiteWalletJni;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.libdohj.params.LitecoinMainNetParams;
import org.libdohj.params.LitecoinRegTestParams;
import org.libdohj.params.LitecoinTestNet3Params;
import org.qortal.api.model.crosschain.PirateChainSendRequest;
import org.qortal.controller.PirateChainWalletController;
import org.qortal.crosschain.PirateLightClient.Server;
import org.qortal.crosschain.PirateLightClient.Server.ConnectionType;
import org.qortal.settings.Settings;
import java.util.*;

public class PirateChain extends Bitcoiny {

	public static final String CURRENCY_CODE = "ARRR";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(10000); // 0.0001 ARRR per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 50000000; // 0.5 ARRR minimum order, to avoid dust errors // TODO: may need calibration

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 10000L; // 0.0001 ARRR
	private static final long NON_MAINNET_FEE = 10000L; // 0.0001 ARRR

	private static final Map<ConnectionType, Integer> DEFAULT_LITEWALLET_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.TCP, 9067);
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.SSL, 443);
	}

	public enum PirateChainNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return LitecoinMainNetParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
						// Servers chosen on NO BASIS WHATSOEVER from various sources!
						new Server("lightd.pirate.black", ConnectionType.SSL, 443));
			}

			@Override
			public String getGenesisHash() {
				return "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				// TODO: This will need to be replaced with something better in the near future!
				return MAINNET_FEE;
			}
		},
		TEST3 {
			@Override
			public NetworkParameters getParams() {
				return LitecoinTestNet3Params.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList();
			}

			@Override
			public String getGenesisHash() {
				return "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		},
		REGTEST {
			@Override
			public NetworkParameters getParams() {
				return LitecoinRegTestParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
						new Server("localhost", ConnectionType.TCP, 9067),
						new Server("localhost", ConnectionType.SSL, 443));
			}

			@Override
			public String getGenesisHash() {
				// This is unique to each regtest instance
				return null;
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		};

		public abstract NetworkParameters getParams();
		public abstract Collection<Server> getServers();
		public abstract String getGenesisHash();
		public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;
	}

	private static PirateChain instance;

	private final PirateChainNet pirateChainNet;

	// Constructors and instance

	private PirateChain(PirateChainNet pirateChainNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode);
		this.pirateChainNet = pirateChainNet;

		LOGGER.info(() -> String.format("Starting Pirate Chain support using %s", this.pirateChainNet.name()));
	}

	public static synchronized PirateChain getInstance() {
		if (instance == null) {
			PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();

			BitcoinyBlockchainProvider pirateLightClient = new PirateLightClient("PirateChain-" + pirateChainNet.name(), pirateChainNet.getGenesisHash(), pirateChainNet.getServers(), DEFAULT_LITEWALLET_PORTS);
			Context bitcoinjContext = new Context(pirateChainNet.getParams());

			instance = new PirateChain(pirateChainNet, pirateLightClient, bitcoinjContext, CURRENCY_CODE);

			pirateLightClient.setBlockchain(instance);
		}

		return instance;
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		instance = null;
	}

	// Actual useful methods for use by other classes

	/** Default Litecoin fee is lower than Bitcoin: only 10sats/byte. */
	@Override
	public Coin getFeePerKb() {
		return DEFAULT_FEE_PER_KB;
	}

	@Override
	public long getMinimumOrderAmount() {
		return MINIMUM_ORDER_AMOUNT;
	}

	/**
	 * Returns estimated LTC fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.pirateChainNet.getP2shFee(timestamp);
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if balance unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		return this.blockchainProvider.getConfirmedAddressBalance(base58Address);
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public int getMedianBlockTime() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();

		// Grab latest 11 blocks
		List<Long> blockTimestamps = this.blockchainProvider.getBlockTimestamps(height - 11, 11);
		if (blockTimestamps.size() < 11)
			throw new ForeignBlockchainException("Not enough blocks to determine median block time");

		// Descending order
		blockTimestamps.sort((a, b) -> Long.compare(b, a));

		// Pick median
		return Math.toIntExact(blockTimestamps.get(5));
	}

	/**
	 * Returns list of compact blocks
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public List<CompactFormats.CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException {
		return this.blockchainProvider.getCompactBlocks(startHeight, count);
	}

	public Long getWalletBalance(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();
			walletController.ensureSynchronized();

			// Get balance
			String response = LiteWalletJni.execute("balance", "");
			JSONObject json = new JSONObject(response);
			if (json.has("zbalance")) {
				return json.getLong("zbalance");
			}

			throw new ForeignBlockchainException("Unable to determine balance");
		}
	}

	public List<SimpleTransaction> getWalletTransactions(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();
			walletController.ensureSynchronized();

			List<SimpleTransaction> transactions = new ArrayList<>();

			// Get transactions list
			String response = LiteWalletJni.execute("list", "");
			JSONArray transactionsJson = new JSONArray(response);
			if (transactionsJson != null) {
				for (int i = 0; i < transactionsJson.length(); i++) {
					JSONObject transactionJson = transactionsJson.getJSONObject(i);

					if (transactionJson.has("txid")) {
						String txId = transactionJson.getString("txid");
						Long timestamp = transactionJson.getLong("datetime");
						Long amount = transactionJson.getLong("amount");
						Long fee = transactionJson.getLong("fee");

						if (transactionJson.has("incoming_metadata")) {
							JSONArray incomingMetadatas = transactionJson.getJSONArray("incoming_metadata");
							if (incomingMetadatas != null) {
								for (int j = 0; j < incomingMetadatas.length(); j++) {
									JSONObject incomingMetadata = incomingMetadatas.getJSONObject(i);
									if (incomingMetadata.has("value")) {
										//String address = incomingMetadata.getString("address");
										Long value = incomingMetadata.getLong("value");
										//String memo = incomingMetadata.getString("memo");

										amount = value; // TODO: figure out how to parse transactions with multiple incomingMetadata entries
									}
								}
							}
						}

						// TODO: JSONArray outgoingMetadatas = transactionJson.getJSONArray("outgoing_metadata");

						SimpleTransaction transaction = new SimpleTransaction(txId, Math.toIntExact(timestamp), amount, fee, null, null);
						transactions.add(transaction);
					}
				}
			}

			return transactions;
		}
	}

	public String getWalletAddress(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();

			return walletController.getCurrentWallet().getWalletAddress();
		}
	}

	public String sendCoins(PirateChainSendRequest pirateChainSendRequest) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.initWithEntropy58(pirateChainSendRequest.entropy58);
		walletController.ensureInitialized();
		walletController.ensureSynchronized();

		// Unlock wallet
		walletController.getCurrentWallet().unlock();

		// Build spend
		JSONObject txn = new JSONObject();
		txn.put("input", walletController.getCurrentWallet().getWalletAddress());
		//txn.put("fee", pirateChainSendRequest.feePerByte); // We likely need to specify total fee, instead of per byte

		JSONObject output = new JSONObject();
		output.put("address", pirateChainSendRequest.receivingAddress);
		output.put("amount", pirateChainSendRequest.arrrAmount);
		output.put("memo", pirateChainSendRequest.memo);

		JSONArray outputs = new JSONArray();
		outputs.put(output);
		txn.put("output", outputs);

		String txnString = txn.toString();

		// Send the coins
		String response = LiteWalletJni.execute("send", txnString);
		JSONObject json = new JSONObject(response);
		try {
			if (json.has("txid")) { // Success
				return json.getString("txid");
			}
			else if (json.has("error")) {
				String error = json.getString("error");
				throw new ForeignBlockchainException(error);
			}

		} catch (JSONException e) {
			throw new ForeignBlockchainException(e.getMessage());
		}

		throw new ForeignBlockchainException("Something went wrong");
	}

	public String getSyncStatus(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);

			return walletController.getSyncStatus();
		}
	}

}