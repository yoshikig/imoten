/*
 * imoten - i mode.net mail tensou(forward)
 *
 * Copyright (C) 2013, 2014 ryu aka 508.P905 (http://code.google.com/p/imoten/)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package immf;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeMessage;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.sun.mail.imap.IMAPFolder;

public class SpmodeImapReader extends SpmodeReader implements UncaughtExceptionHandler{
	public static final String sentHeader = "X-IMOTEN-FOLDER-SENT";
	private static final Log log = LogFactory.getLog(ServerMain.class);
	private final String sentFolder = "Sent";
	private final String[] systemFolders = { sentFolder, "Drafts", "Trash", "迷惑メール＿ドコモ用" };

	private Config conf;
	private StatusManager status;

	private Properties props;
	private String myname;
	private String passwd;
	private Store store = null;
	
	private boolean doingImapIdle;
	private List<Folder> folderList;
	private TreeMap<String, Message> allMessages;
	private TreeMap<String, Message> latestMessages;
	private String lastPollUid;
	private boolean isInitialized = false;
	
	private LinkedList<Message> imodeRecvMessages;
	private LinkedList<Message> imodeSendMessages;
	private LinkedList<Message> pop3RecvMessages;
	private List<String> syncFolders;
	
	public SpmodeImapReader(ServerMain server){
		this.conf = server.conf;
		this.status = server.status;

		this.props = new Properties();
		log.info("spmode: imap");
		props.setProperty("mail.imap.host", "imap.spmode.ne.jp");
		props.setProperty("mail.imap.port", "993");
		props.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.setProperty("mail.imap.socketFactory.fallback", "false");
		props.setProperty("mail.imap.socketFactory.port", "993");
		props.setProperty("mail.imap.connectiontimeout", Integer.toString(conf.getImapConnectTimeoutSec()*1000));
		props.setProperty("mail.imap.timeout", Integer.toString(conf.getImapTimeoutSec()*1000));

		this.myname = conf.getDocomoId();
		this.passwd = conf.getDocomoPasswd();
		
		this.doingImapIdle = false;
		this.folderList = new LinkedList<Folder>();
		this.allMessages = new TreeMap<String, Message>();
		this.latestMessages = new TreeMap<String, Message>();
		this.lastPollUid = "";

		this.imodeRecvMessages = new LinkedList<Message>();
		this.imodeSendMessages = new LinkedList<Message>();
		this.pop3RecvMessages = new LinkedList<Message>();
		this.syncFolders = new ArrayList<String>();
	}
	
	public void connect() throws MessagingException {
		if(store==null || !store.isConnected()){
			Session session = Session.getInstance(props, null);
			session.setDebug(conf.isMailDebugEnable());
		
			store = session.getStore("imap");
			store.connect(myname, passwd);
		}
	}
		
	public void open(boolean readSent) throws MessagingException {
		connect();
		
		folderList.clear();
		latestMessages.clear();
		
		Folder rootFolder = store.getDefaultFolder();
		Folder[] subscribedFolders = rootFolder.listSubscribed();
		List<String> ignoreFolders = new ArrayList<String>();
		if (conf.getSpmodeImapSystemFolders().size()>0) {
			ignoreFolders = conf.getSpmodeImapSystemFolders();
		}else{
			for (String s : systemFolders) {
				ignoreFolders.add(s);
			}
		}
		ignoreFolders.addAll(syncFolders);
		// ここ(readSent)だけ座りが悪い
		if(readSent){
			ignoreFolders.remove(sentFolder);
		}
		for (Folder f : subscribedFolders) {
			String folderName = f.getFullName();
			boolean ignore = false;
			for (String s : ignoreFolders) {
				if (folderName.equalsIgnoreCase(s)){
					ignore = true;
					break;
				}
			}
			if (ignore) {
				continue;
			}
			try{
				f.open(Folder.READ_WRITE);
			}catch(MessagingException me){
				f.open(Folder.READ_ONLY);
			}
			folderList.add(f);
		}
	}

	public IMAPFolder getSentFolder() {
		// 接続待ち
		while (store==null) {
			try{
				Thread.sleep(100);
			}catch (Exception e) {}
		}
		try{
			Folder rootFolder = store.getDefaultFolder();
			Folder folder = rootFolder.getFolder(sentFolder);
			return (IMAPFolder)folder;
		} catch (MessagingException me){
			return null;
		}
	}
	
	public void getMessages() throws MessagingException {
		String lastId = getLastId();
		boolean isSent;
		folderLoop:
		for (Folder folder : folderList) {
			if(folder.getFullName().equalsIgnoreCase(sentFolder)){
				isSent = true;
			}else{
				isSent = false;
			}
			
			Message messages[] = folder.getMessages();
			int messageLength = folder.getMessageCount();
			int start = -1;
			Message msg;
			for (int index = messageLength-1; index >= 0; index--) {
				msg = messages[index];
				String thisId = getUid(folder, msg);
				if(index == messageLength-1){
					latestMessages.put(thisId, msg);
					if(StringUtils.isBlank(lastId)){
						continue folderLoop;
					}
				}
				log.info("ID探索("+folder.getFullName()+"):"+index+","+thisId);
				// もともと long で拾える値だから文字列比較ではなく数値比較をすればよいのだが・・・
				if(thisId.compareToIgnoreCase(lastId)<=0){
					start = index;
					break;
				}
			}

			for (int index = start + 1; index < messageLength; index++) {
				msg = messages[index];
				boolean seen = msg.isSet(Flags.Flag.SEEN);
				String id = getUid(folder, msg);

				StringBuilder maildata = Util.dumpMessage(msg);
				log.info("取得メール情報:"+id+"\n"+maildata);

				if(isSent){
					/*
					 * X-IMOTEN-FOLDER-SENT 以下の判定で使用
					 * forward.sent
					 * forward.sent.subject.prefix
					 * forward.sent.subject.suffix
					 */
					Message sentMsg = new MimeMessage((MimeMessage)msg);
					sentMsg.addHeader(sentHeader, "true");
					allMessages.put(id, sentMsg);
				}else{
					allMessages.put(id, msg);
				}

				// 既読フラグのセット
				if(folder.getMode()!=Folder.READ_WRITE){
					continue;
				}
				try{
					if(conf.isSpmodeForceSeen()){
						/*
						 * アクセスしたら強制的に既読になってしまう場合とそうでない場合あり。
						 * 条件がよくわからないので常に setFlag() をすることにした。
						 */
						seen = true;
					}
					msg.setFlag(Flags.Flag.SEEN, seen);
					if(seen){
						log.info("メッセージ"+id+"は開封済み状態です");
					}else{
						log.info("メッセージ"+id+"を未読状態に戻しました");
					}
				}catch(MessagingException e){}
			}
		}
		if(StringUtils.isBlank(lastId)){
			log.info("lastspmsgUidが空なのでメール取得をスキップし、次回受信したメールから転送を開始します。");
		}
	}
	
	public int getMessageCount() {
		return allMessages.size();
	}
	
	public Message readMessage() {
		Map.Entry<String, Message> e = allMessages.pollFirstEntry();
		Message m = e.getValue(); 
		this.lastPollUid = e.getKey();
		return m;
	}

	public void restoreMessage(String id, Message msg) {
		allMessages.put(id, msg);
	}

	public String getLatestId() {
		if (!lastPollUid.isEmpty()){
			return lastPollUid;
		} else if (latestMessages.size()>0){
			return latestMessages.lastKey();
		}else{
			return "";
		}
	}
	
	public void updateLastId() {
		String thisId = getLatestId();
		if(thisId.compareToIgnoreCase(getLastId())>0){
			this.status.setLastSpMsgUid(thisId);
			log.info("lastspmsgUidを更新しました");
			try{
				this.status.save();
				log.info("statusファイルを保存しました");
			}catch (Exception e) {
				log.error("Status File save Error.",e);
			}
		}
	}
	
	protected String getLastId() {
		return this.status.getLastSpMsgUid();
	}
	
	public void waitMessage() {
		if (folderList.size()==0){
			log.warn("読み取り対象のフォルダが存在しないためメールの監視を中止します");
			// 安直に無限ループ待ち
			while (true) {
				try{
					Thread.sleep(60*60*24*1000);
				}catch (Exception e) {}
			}
		}
		log.info("IMAP IDLE開始");
		for (Folder folder : folderList) {
			if (folder.isOpen()){
				this.doingImapIdle = true;
				IMAPFolder f = (IMAPFolder)folder;
				Thread DoImapIdle = new Thread(new DoImapIdle(f));
				DoImapIdle.setName("SpmodeChecker[imap]");
				DoImapIdle.setUncaughtExceptionHandler(this);
				DoImapIdle.start();
			}
		}
		while (this.doingImapIdle) {
			try{
				Thread.sleep(1000);
			}catch (Exception e) {}
		}
		log.info("IMAP IDLE完了");
	}
	
	public void close() {
		closeAllFolder();
	}
	private void closeAllFolder() {
		for (Folder folder : folderList) {
			try{
				if(folder.isOpen()){
					folder.close(false);
				}
			}catch (MessagingException e){}
		}
	}
	
	public boolean isReady() {
		return this.isInitialized;
	}
	
	public void setInitialized() {
		this.isInitialized = true;
	}
	
	private String getUid(Folder f, Message m) throws MessagingException{
		long uid = getLongUid(f, m);
		return String.format("%019d", uid);
	}
	private long getLongUid(Folder f, Message m) throws MessagingException{
		long uid = ((IMAPFolder)f).getUID(m);
		return uid;
	}
	
	class DoImapIdle implements Runnable {
		private IMAPFolder folder;
		public DoImapIdle(IMAPFolder f) {
			folder = f;
		}
		public void run() {
			try {
				ImapIdleTimer timer = new ImapIdleTimer(folder);
				Thread ImapIdleTimer = new Thread(timer);
				ImapIdleTimer.setName("SpmodeChecker[imap]");
				ImapIdleTimer.start();
				//log.info("IMAP IDLE開始("+folder.getFullName()+")");
				folder.idle(true);
				//log.info("IMAP IDLE完了("+folder.getFullName()+")");
				timer.done();
			}catch(FolderClosedException fce){
				log.info("IMAP IDLE中断(サーバとの接続が切断されました)");
			}catch(Exception e){
				log.warn("例外発生",e);
			}
			try{
				if(folder.isOpen()){
					folder.close(false);
				}
			}catch (MessagingException e){}
			throw new RuntimeException();
		}
	}
	class ImapIdleTimer implements Runnable {
		private IMAPFolder folder;
		private boolean open = true;
		public ImapIdleTimer(IMAPFolder f) {
			folder = f;
		}
		public void run() {
			try{
				Thread.sleep(conf.getImapIdleTimeoutSec()*1000);
			}catch(Exception e){}
			try{
				if (open) {
					//log.info("フォルダクローズ");
					folder.close(false);
				}
			}catch(Exception e){}
		}
		public void done() {
			open = false;
		}
	}
	public void uncaughtException(Thread thread, Throwable throwable) {
		//log.info("IMAPフォルダクローズ実行");
		closeAllFolder();
		this.doingImapIdle = false;
	}
	
	/*
	 * iモード、spモード(pop3)の送受信メールをIMAPに同期するための処理
	 */
	public void addSyncFolder(String folderName) {
		this.syncFolders.add(folderName);
		log.info("["+folderName+"]は同期先フォルダのためIMAPチェック対象外です。");

		// 接続待ち
		while (store==null) {
			try{
				Thread.sleep(100);
			}catch (Exception e) {}
		}
		
		// 購読状態のチェック
		boolean doSubscribe = conf.isSpmodeSubscribeSyncFolder();
		try{
			Folder rootFolder = store.getDefaultFolder();
			Folder folder = rootFolder.getFolder(folderName);
			if (folder.exists()) {
				if (!doSubscribe && folder.isSubscribed()) {
					// unsubscribe 指示
					folder.setSubscribed(false);
					log.info("["+folderName+"]をunsubscribeしました。");
				} else if (doSubscribe && !folder.isSubscribed()){
					// subscribe 指示
					folder.setSubscribed(true);
					log.info("["+folderName+"]をsubscribeしました。");
				}
			}
		} catch (MessagingException me){
			if (doSubscribe) {
				log.warn("["+folderName+"]のsubscribeに失敗しました。",me);
			} else {
				log.warn("["+folderName+"]のunsubscribeに失敗しました。",me);
			}
		}
	}
	
	public void putImodeMail(ImodeMail mail) {
		int folderId = mail.getFolderId();
		if (folderId==ImodeNetClient.FolderIdSent) {
			imodeSendMessages.add(mail.getMessage());
			if (imodeSendMessages.size()>=10){
				saveImodeMail();
			}
		}else{
			imodeRecvMessages.add(mail.getMessage());
			if (imodeRecvMessages.size()>=10){
				saveImodeMail();
			}
		}
	}
	public void putPop3Mail(Message msg) {
		pop3RecvMessages.add(msg);
		if (pop3RecvMessages.size()>=10){
			savePop3Mail();
		}
	}

	public void saveImodeMail() {
		saveMail(conf.getImodenetSyncFolder(), imodeRecvMessages);
		saveMail(conf.getImodenetSyncSentFolder(), imodeSendMessages);
	}
	public void savePop3Mail() {
		saveMail(conf.getSpmodePop3SyncFolder(), pop3RecvMessages);
	}
	private void saveMail(String folderName, LinkedList<Message> messages){
		synchronized (messages) {
			int count = messages.size();
			if (count>0) {
				// 接続待ち
				while (store==null) {
					try{
						Thread.sleep(100);
					}catch (Exception e) {}
				}

				try{
					Folder rootFolder = store.getDefaultFolder();
					IMAPFolder folder = (IMAPFolder) rootFolder.getFolder(folderName);
					if(!folder.exists()){
						folder.create(Folder.HOLDS_MESSAGES);
						if (conf.isSpmodeSubscribeSyncFolder()) {
							folder.setSubscribed(true);
						}
					}
					folder.open(Folder.READ_WRITE);
					Message[] ma = folder.addMessages(messages.toArray(new Message[0]));
					for (Message m : ma){
						m.setFlag(Flags.Flag.SEEN, true);
						this.lastPollUid = getUid(folder, m);
						log.info("保存メッセージのUID:"+this.lastPollUid);
					}
					folder.close(false);
					
					log.info("ドコモメール["+folderName+"]にメールを "+count+"通 保存しました");
					updateLastId();

				} catch (MessagingException me){
					log.error("saveMail:"+folderName,me);
				}
				messages.clear();
			}
		}
	}
}