/*
 * imoten - i mode.net mail tensou(forward)
 *
 * Copyright (C) 2010 shoozhoo (http://code.google.com/p/imoten/)
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

import immf.growl.GrowlNotifier;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class ImodeCheckMail implements Runnable{
	private static final Log log = LogFactory.getLog(ServerMain.class);

	private ImodeNetClient client;
	private Config conf;
	private StatusManager status;
	private SkypeForwarder skypeForwarder;
	private ImKayacNotifier imKayacNotifier;
	private GrowlNotifier prowlNotifier;
	private GrowlNotifier nmaNotifier;
	private AppNotifications appNotifications;
	private int numForwardSite;
	private Map<Config, ForwardMailPicker> forwarders = new HashMap<Config, ForwardMailPicker>();
	private Map<Config, List<String>> ignoreDomainsMap = new HashMap<Config, List<String>>();
	private SpmodeImapReader imapreader = null;
	private boolean syncImapFolder = false;
	private boolean syncImapOnly = false;

	public ImodeCheckMail(ServerMain server){
		this.client = server.client;
		this.conf = server.conf;
		this.status = server.status;
		this.skypeForwarder = server.skypeForwarder;
		this.imKayacNotifier = server.imKayacNotifier;
		this.prowlNotifier = server.prowlNotifier;
		this.nmaNotifier = server.nmaNotifier;
		this.appNotifications = server.appNotifications;
		this.numForwardSite = conf.countForwardSite();
		this.forwarders = server.forwarders;
		this.ignoreDomainsMap = server.ignoreDomainsMap;
		this.syncImapFolder = this.conf.isImodenetSyncImap();
		this.syncImapOnly = this.conf.isImodenetSyncOnly();
	}

	public void run() {
		Date lastUpdate = null;
		while(true){
			if(lastUpdate != null){
				// 接続フラグを見るためにステータスファイルをチェック
				try{
					this.status.load();
				}catch (Exception e) {}
				long diff = System.currentTimeMillis() - lastUpdate.getTime();
				if(diff < conf.getForceCheckIntervalSec()*1000 && !this.status.needConnect()){
					//接続フラグが立っていなければ次のチェックまで待つ
					try{
						Thread.sleep(conf.getCheckFileIntervalSec()*1000);
					}catch (Exception e) {}
					continue;
				}
			}

			Map<Integer,List<String>> mailIdListMap = null;
			try{
				// メールID一覧取得(降順)
				mailIdListMap = this.client.getMailIdList();
				this.client.checkAddressBook();

			}catch (LoginException e) {
				log.error("ログインエラー",e);
				if(lastUpdate == null){
					// 起動直後はcookieが切れている可能性があるのでクッキーを破棄してリトライ
					log.info("起動直後は5秒後にログイン処理を行います。");
					try{
						Thread.sleep(1000*5);
					}catch (Exception ex) {
					}
					lastUpdate = new Date();
					continue;
				}
				try{
					// 別の場所でログインされた
					log.info("Wait "+this.conf.getLoginRetryIntervalSec()+" sec.");
					Thread.sleep(this.conf.getLoginRetryIntervalSec()*1000);
				}catch (Exception ex) {}
				continue;
			}catch (Exception e) {
				log.error("Get Mail ID List error.", e);
				try{
					Thread.sleep(this.conf.getLoginRetryIntervalSec()*1000);
				}catch (Exception ex) {}
				continue;
			}

			String newestId="0";	// 次のlastIdを求める
			Iterator<Integer> folderIdIte =  mailIdListMap.keySet().iterator();
			while(folderIdIte.hasNext()){
				// フォルダごとに処理
				Integer fid = folderIdIte.next();
				List<String> mailIdList = mailIdListMap.get(fid);

				folderProc(fid,mailIdList);

				if(!mailIdList.isEmpty()){
					String newestInFolder = mailIdList.get(0);
					if(newestId.compareToIgnoreCase(newestInFolder)<0){
						newestId = newestInFolder;
					}
				}
			}
			
			// IMAP同期
			if (syncImapFolder) {
				try {
					imapreader.saveImodeMail();
				}catch (Exception e1){
					log.error("IMAP同期処理でエラー発生",e1);
				}
			}

			// 接続フラグのリセット
			this.status.resetNeedConnect();

			// status.ini の更新
			if(StringUtils.isBlank(this.status.getLastMailId())){
				this.status.setLastMailId(newestId);
				log.info("LastMailIdが空なので、次のメールから転送を開始します。");
			}
			String lastId = this.status.getLastMailId();
			if(lastId!=null && !lastId.equals(newestId)){
				this.status.setLastMailId(newestId);
				log.info("LastMailId("+newestId+")に更新しました");
			}
			try{
				if(this.conf.isSaveCookie()){
					this.status.setCookies(client.getCookies());
				}
				this.status.save();
				log.info("statusファイルを保存しました");
			}catch (Exception e) {
				log.error("Status File save Error.",e);
			}
			lastUpdate = new Date();

			// 次のチェックまで待つ
			try{
				Thread.sleep(conf.getCheckIntervalSec()*1000);
			}catch (Exception e) {}
		}

	}

	private void folderProc(Integer fid, List<String> mailIdList){
		String lastId = this.status.getLastMailId();
		log.info("FolderID "+fid+"  受信メールIDの数:"+mailIdList.size()+"  lastId:"+lastId);

		String newestId = "";

		if(StringUtils.isBlank(lastId)){
			if(!mailIdList.isEmpty()){
				// 最初の起動では現在の最新メールの次から転送処理する
				if(newestId.compareToIgnoreCase(mailIdList.get(0))<0){
					return;
				}
			}else{
				// メールがひとつも無かった
				return;
			}
		}else{
			List<String> forwardIdList = new LinkedList<String>();
			for (String id : mailIdList) {
				if(lastId.compareToIgnoreCase(id)<0){
					// 昇順に入れていく
					forwardIdList.add(0, id);
				}
			}
			log.info("転送するメールIDの数 "+forwardIdList.size());
			appNotifications.pushPrepare(fid, forwardIdList.size());
			for (String id : forwardIdList) {
				this.forward(fid,id);
			}
		}
	}

	/*
	 * メールをダウンロードして送信
	 */
	private void forward(int folderId, String mailId){
		if(folderId==ImodeNetClient.FolderIdSent
				&& numForwardSite == 1
				&& !this.conf.isForwardSent()){
			// 送信メールは転送しない
			return;
		}
		ImodeMail mail = null;
		try{
			// download
			mail = this.client.getMail(folderId, mailId);
			if(log.isInfoEnabled()){
				log.info("Downloaded Mail ########");
				log.info(mail.toLoggingString());
				log.info("########################");
			}
			// IMAP同期
			if(this.syncImapFolder){
				try{
					imapreader.putImodeMail(mail);
				} catch (Exception e1){
					log.error("IMAP同期処理でエラー発生",e1);
				}
				if(this.syncImapOnly){
					return;
				}
			}
		}catch (Exception e) {
			log.warn("i mode.net mailId["+mailId+"] download Error.",e);
			return;
		}

		String from = mail.getFromAddr().getAddress();
		List<String> ignoreDomains = new ArrayList<String>();
		try{
			// 送信
			for (Map.Entry<Config, ForwardMailPicker> f : forwarders.entrySet()) {
				Config forwardConf = f.getKey();
				int id = forwardConf.getConfigId();

				if(folderId==ImodeNetClient.FolderIdSent
						&& !forwardConf.isForwardSent()){
					// 送信メールは転送しない
					continue;
				}
				
				if(forwardConf.getForwardOnly()!=Config.ForwardOnly.Imode
						&&forwardConf.getForwardOnly()!=Config.ForwardOnly.BOTH){
					continue;
				}
				
				//  転送抑止ドメインリストと比較して送信可否判定
				boolean notForward = false;
				ignoreDomains = ignoreDomainsMap.get(forwardConf);
				for (String domain : ignoreDomains) {
					if(from.endsWith(domain)){
						log.info("送信者:"+from+" のメール転送中止["+id+"]");
						notForward = true;
					}
				}
				if(notForward){
					continue;
				}
				
				ForwardMailPicker fpicker = f.getValue();
				if(forwardConf.isForwardAsync()){
					// 別スレッド(ForwardMailPicker)で送信。送信失敗時はリトライあり。
					fpicker.add(mail);
				}else{
					ImodeForwardMail forwardMail = new ImodeForwardMail(mail,forwardConf);
					forwardMail.send();
					if(numForwardSite>1){
						log.info("転送処理完了["+id+"]");
					}
				}
			}

		}catch (Exception e) {
			log.error("mail["+mailId+"] forward Error.",e);
			return;
		}

		//  転送抑止ドメインリストと比較してPush送信可否判定
		ignoreDomains = ignoreDomainsMap.get(this.conf);
		for (String domain : ignoreDomains) {
			if(from.endsWith(domain)){
				this.appNotifications.pushError(folderId);
				return;
			}
		}

		try{
			this.skypeForwarder.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] skype forward Error.",e);
			return;
		}

		try{
			this.imKayacNotifier.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] im.kayac forward Error.",e);
			return;
		}

		try{
			this.prowlNotifier.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] Prowl forward Error.",e);
			return;
		}

		try{
			this.nmaNotifier.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] NMA forward Error.",e);
			return;
		}

		try{
			this.appNotifications.push(folderId, mail);
		}catch (Exception e) {
			this.appNotifications.pushError(folderId);
			log.error("mail["+mailId+"] AppNotifications push Error.",e);
			return;
		}

		try{
			// 負荷をかけないように
			Thread.sleep(1000);
		}catch (Exception e) {}
	}

	public void setImapReader(SpmodeImapReader imapreader){
		this.imapreader = imapreader;
	}
}
