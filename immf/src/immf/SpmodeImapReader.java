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
	private static final Log log = LogFactory.getLog(ServerMain.class);
	private final String[] systemFolders = { "Sent", "Drafts", "Trash" };

	private Config conf;
	private StatusManager status;

	private Properties props;
	private String myname;
	private String passwd;
	
	private boolean doingImapIdle;
	private List<Folder> folderList;
	private TreeMap<String, Message> allMessages;
	private TreeMap<String, Message> latestMessages;
	private String lastPollUid;
	
	public SpmodeImapReader(ServerMain server){
		this.conf = server.conf;
		this.status = server.status;

		this.props = new Properties();
		log.info("spmode: imap");
		props.setProperty("mail.imap.host", "imap2.spmode.ne.jp");
		props.setProperty("mail.imap.port", "993");
		props.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.setProperty("mail.imap.socketFactory.fallback", "false");
		props.setProperty("mail.imap.socketFactory.port", "993");

		// XXX 設定可能にする？
		//props.setProperty("mail.imap.connectiontimeout", XXX);
		//props.setProperty("mail.imap.timeout", XXX);

		this.myname = conf.getDocomoId();
		this.passwd = conf.getDocomoPasswd();
		
		this.doingImapIdle = false;
		this.folderList = new LinkedList<Folder>();
		this.allMessages = new TreeMap<String, Message>();
		this.latestMessages = new TreeMap<String, Message>();
		this.lastPollUid = "";

	}
	
	public Store connect(Store str) throws MessagingException {
		
		Session session = null;
		Store store = null;
		if(str==null || !str.isConnected()){
			session = Session.getInstance(props, null);
			session.setDebug(conf.isMailDebugEnable());
		
			store = session.getStore("imap");
			store.connect(myname, passwd);
		} else {
			store = str;
		}
		
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
		return store;
	}

	public void getMessages() throws MessagingException {
		String lastId = getLastId();
		folderLoop:
		for (Folder folder : folderList) {
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
						log.info("lastId="+lastId);
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
				String id = "";
				try {
					byte contentData[] = Util.inputstream2bytes(msg.getInputStream());
					log.info("Content-Type:"+msg.getContentType());
					log.info("Content[\n"+new String(contentData)+"\n]");
				}catch(Exception e){}

				id = getUid(folder, msg);
				allMessages.put(id, msg);

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
		this.status.setLastSpMsgUid(getLatestId());
		log.info("lastspmsgUidを更新しました");
		try{
			this.status.save();
			log.info("statusファイルを保存しました");
		}catch (Exception e) {
			log.error("Status File save Error.",e);
		}
	}
	
	protected String getLastId() {
		return this.status.getLastSpMsgUid();
	}
	
	public void waitMessage() {
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
				// 10分弱待つ
				Thread.sleep(590*1000);
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
}