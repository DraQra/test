/*
 * Copyright 2013 Eediom Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.logpresso.sdk.mcs;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.araqne.log.api.AbstractLogger;
import org.araqne.log.api.Log;
import org.araqne.log.api.LogPipe;
import org.araqne.log.api.Logger;
import org.araqne.log.api.LoggerFactory;
import org.araqne.log.api.LoggerSpecification;
import org.araqne.log.api.LoggerStopReason;
import org.araqne.log.api.MultilineLogExtractor;
import org.araqne.log.api.Reconfigurable;
import org.logpresso.ftp.FtpProfile;
import org.logpresso.ftp.FtpProfileRegistry;
import org.logpresso.ftp.FtpProfileRegistryListener;
import org.logpresso.ftp.FtpRateLimitInputStream;
import org.logpresso.ftp.FtpRateLimitService;
import org.logpresso.ftp.FtpUtils;

public class MCSFtpRotationFileLogger extends AbstractLogger implements LogPipe, Reconfigurable, FtpProfileRegistryListener {
	private final org.slf4j.Logger slog = org.slf4j.LoggerFactory.getLogger(MCSFtpRotationFileLogger.class);
	private final File dataDir;
	private String filePath;

	// real destination file after follow symlinks
	private String realFilePath;

	private FtpProfileRegistry profileRegistry;
	private FtpRateLimitService limitService;

	private String charset;

	private MultilineLogExtractor extractor;

	private FTPClient client;

	public MCSFtpRotationFileLogger(LoggerSpecification spec, LoggerFactory factory, FtpProfileRegistry profileRegistry,
			FtpRateLimitService limitService) {
		super(spec, factory);
		this.profileRegistry = profileRegistry;
		this.limitService = limitService;

		this.dataDir = new File(System.getProperty("araqne.data.dir"), "mcs-ftp");
		this.dataDir.mkdirs();

		init(getConfigs());
	}

	private void init(Map<String, String> c) {
		MultilineLogExtractor extractor = new MultilineLogExtractor(this, this);

		// optional
		String datePatternRegex = c.get("date_pattern");
		if (datePatternRegex != null) {
			extractor.setDateMatcher(Pattern.compile(datePatternRegex).matcher(""));
		}

		// optional
		String dateLocale = c.get("date_locale");
		if (dateLocale == null)
			dateLocale = "en";

		// optional
		String dateFormatString = c.get("date_format");
		String timeZone = c.get("timezone");
		if (dateFormatString != null)
			extractor.setDateFormat(new SimpleDateFormat(dateFormatString, new Locale(dateLocale)), timeZone);

		// optional
		String beginRegex = c.get("begin_regex");
		if (beginRegex != null)
			extractor.setBeginMatcher(Pattern.compile(beginRegex).matcher(""));

		String endRegex = c.get("end_regex");
		if (endRegex != null)
			extractor.setEndMatcher(Pattern.compile(endRegex).matcher(""));

		// optional
		charset = c.get("charset");
		if (charset == null)
			charset = "utf-8";

		extractor.setCharset(charset);

		this.filePath = c.get("file_path");
		this.extractor = extractor;
	}

	@Override
	public void onUpdated(FtpProfile oldProfile, FtpProfile newProfile) {
		if (newProfile.getName().equals(getConfigs().get("ftp_profile"))) {
			if (!oldProfile.getHost().equals(newProfile.getHost()) || oldProfile.getPort() != newProfile.getPort()) {
				slog.info("mcs ftp: ftp-rotation [{}] turn back to the initial state", getFullName());
				setStates(new HashMap<String, Object>());
			}
		}
	}

	@Override
	public void onConfigChange(Map<String, String> oldConfigs, Map<String, String> newConfigs) {
		init(newConfigs);
		if (!oldConfigs.get("ftp_profile").equals(newConfigs.get("ftp_profile"))
				|| !oldConfigs.get("file_path").equals(newConfigs.get("file_path")))
			setStates(new HashMap<String, Object>());
	}

	@Override
	protected void onStop(LoggerStopReason reason) {
		try {
			slog.debug("mcs ftp: force close client socket of logger [{}]", getFullName());
			client.disconnect();
		} catch (Throwable t) {
		}
	}

	@Override
	public void onLog(Logger logger, Log log) {
		write(log);
	}

	@Override
	public void onLogBatch(Logger logger, Log[] logs) {
		writeBatch(logs);
	}

	@Override
	protected void runOnce() {
		FileInfo currentState = null;
		Map<String, Object> state = getStates();
		String firstLine = (String) state.get("first_line");
		long lastPos = state.get("last_position") != null ? Long.valueOf(state.get("last_position").toString()) : 0L;
		long fileLength = state.get("file_length") != null ? Long.valueOf(state.get("file_length").toString()) : 0L;
		FileInfo lastState = new FileInfo(firstLine, lastPos, fileLength);

		AtomicLong lastPosition = new AtomicLong(0);

		InputStream is = null;
		client = null;
		boolean opened = false;

		try {
			String profileName = getConfigs().get("ftp_profile");
			FtpProfile profile = profileRegistry.getProfile(profileName);
			if (profile == null)
				throw new IllegalStateException("ftp profile not found: " + profileName);

			slog.debug("mcs ftp: ftp-rotation [{}] trying to connect [{}]", getFullName(), profileName);

			client = new FTPClient();
			FtpUtils.connect(client, profile);

			// compare
			currentState = getCurrentInfo(client);
			if (currentState == null)
				return;

			long offset = 0;
			if (lastState != null) {
				//최초 파일 엑세스 또는 파일이 교체된 경우
				if (currentState.firstLine == null || !currentState.firstLine.equals(lastState.firstLine)
						|| currentState.fileLength < lastState.fileLength){
					
					//파일이 교체된 경우
					if(currentState.firstLine != null && lastState.firstLine != null && lastState.firstLine != "" && !currentState.firstLine.equals(lastState.firstLine)){
						// "yyyy-MM-dd-HH"
						Calendar cal = Calendar.getInstance();
						int year = cal.get(Calendar.YEAR);
						int mon = cal.get(Calendar.MONTH)+1;
						int day = cal.get(Calendar.DAY_OF_MONTH);
						int hour = cal.get(Calendar.HOUR_OF_DAY)-1;
						
						String dash = "-";
						StringBuilder hourAgo = new StringBuilder(); 
						hourAgo.append(String.valueOf(year));
						hourAgo.append(dash);
						hourAgo.append(String.format("%02d", mon));
						hourAgo.append(dash);
						hourAgo.append(String.format("%02d", day));
						hourAgo.append(dash);
						hourAgo.append(String.format("%02d", hour));

						//기존 파일의 리네임된 파일 이름
						StringBuilder prevFilePathBuilder = new StringBuilder(); 
						prevFilePathBuilder.append(realFilePath);
						prevFilePathBuilder.append(".");
						prevFilePathBuilder.append(hourAgo.toString());
						
						String prevFilePath = prevFilePathBuilder.toString();
						
						slog.info("mcs ftp: ftp-rotation [{}] loading prevFile [{}]", getFullName(), prevFilePath);
						FTPFile f = findFile(client, prevFilePath);
						
						//리네임된 파일이 있을 경우
						if(f != null){
							InputStream is1 = client.retrieveFileStream(prevFilePath);
							
							lastPosition.set(lastState.lastPosition);
							
							opened = true;
							
							String limitProfileName = getConfigs().get("rate_limit");
							if (limitProfileName != null)
								is = new BufferedInputStream(new FtpRateLimitInputStream(is1, limitProfileName, limitService));
							else
								is = new BufferedInputStream(is1);
							
							//리네임된 파일의 끝부분 마저 수집
							extractor.extract(is, lastPosition);
							setTemporaryFailure(null);
							
							//정규표현식 재설정 후 재수집(마지막 라인 수집을 위함)
							String begin_regex = getConfig().get("begin_regex");
							
							//로그시작 정규표현식 null 설정
							extractor.setBeginMatcher(null);
							
							//재수집(남아 있는 로그가 한 라인이라는 전제, 라인 구분없이 수집)
							extractor.extract(is, lastPosition);
							setTemporaryFailure(null);
							
							//로그시작 정규표현식 원복
							extractor.setBeginMatcher(Pattern.compile(begin_regex).matcher(""));
						}
					}
					
					//최초 엑세스 파일에 대한 offset 조정
					offset = 0;
					
				}else{//리네임되지 않은 기존에 엑세스한 파일인 경우
					offset = lastState.lastPosition;
				}
			}

			lastPosition.set(offset);

			if (offset >= currentState.fileLength) {
				slog.info("mcs ftp: ftp-rotation [{}] no more data from file [{}]", getFullName(), realFilePath);
				return;
			}

			try {
                client.disconnect();
            } catch (Throwable var34) {
                slog.warn("logpresso ftp: unexpected exception while closing client after readFirstLine from [" + getFullName() + "]: " + var34.getMessage());
                slog.info("logpresso ftp: exception detail: ", var34);
            }

            FtpUtils.connect(client, profile);
			
			if (offset != 0)
				client.setRestartOffset(offset);

			String limitProfileName = getConfigs().get("rate_limit");

			slog.debug("mcs ftp: ftp-rotation [{}] loading file [{}]", getFullName(), realFilePath);
			InputStream is1 = client.retrieveFileStream(realFilePath);
			opened = true;

			if (limitProfileName != null)
				is = new BufferedInputStream(new FtpRateLimitInputStream(is1, limitProfileName, limitService));
			else
				is = new BufferedInputStream(is1);

			extractor.extract(is, lastPosition);
			setTemporaryFailure(null);
		} catch (Throwable t) {
			setTemporaryFailure(t);
			slog.error("mcs ftp: ftp rotation logger [" + getFullName() + "] cannot read file", t);
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
				}
			}

			if (currentState != null) {
				updateState(currentState.firstLine, currentState.fileLength, lastPosition.get());
			}

			try {
				if (opened && !client.completePendingCommand()) {
					slog.error("mcs ftp: logger [{}] failed to complete pending command", getFullName());
				}
			} catch (IOException e) {
				slog.error("mcs ftp: logger [{}] cannot complete pending command errorName : [{}]", getFullName(), e.getMessage());
			}

			FtpUtils.disconnect(client);
			client = null;
		}
	}

	public void updateState(String firstLine, long fileLength, long lastPosition) {
		Map<String, Object> m = new HashMap<String, Object>();
		m.put("first_line", firstLine);
		m.put("last_position", lastPosition);
		m.put("file_length", fileLength);
		setStates(m);
	}

	private String readFirstLine(InputStream is) {
		BufferedReader br = null;

		try {
			br = new BufferedReader(new InputStreamReader(is, charset));
			return br.readLine();
		} catch (Throwable t) {
			slog.error("mcs ftp: cannot read first line, ftp-rotation logger [" + getFullName() + "]", t);
			return null;
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
				}
			}

			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
				}
			}
		}
	}

	protected File getLastLogFile() {
		return new File(dataDir, "ftp-rotation-" + getName() + ".lastlog");
	}

	private FileInfo getCurrentInfo(FTPClient client) throws IOException {
		long fileSize = 0;

		this.realFilePath = filePath;

		boolean found = false;
		while (true) {
			slog.debug("mcs ftp: checking path [{}]", realFilePath);
			FTPFile f = findFile(client, realFilePath);
			if (f == null)
				break;

			if (f.isFile()) {
				fileSize = f.getSize();
				found = true;
				slog.debug("mcs ftp: found path [{}] size [{}]", realFilePath, fileSize);
				break;
			} 
			if (f.isSymbolicLink()) {
				break;
			}	
			
			int p = this.realFilePath.lastIndexOf('/');
			if (p < 0){
				break;
			}

			String oldPath = realFilePath;
			realFilePath = realFilePath.substring(0, p + 1) + f.getLink();
			if (oldPath.equals(realFilePath)){
				break;
			}	
			slog.debug("mcs ftp: symlink found path [{}] link [{}]", realFilePath, f.getLink());
		
		}

		if (!found) {
			slog.debug("mcs ftp: rotation logger [{}] file [{}] not found", getFullName(), filePath);
			return null;
		}else{
			String firstLine = null;
			try {
				InputStream is = client.retrieveFileStream(realFilePath);
				firstLine = readFirstLine(is);
			} catch (IOException e) {
				slog.error("mcs ftp: logger [{}] cannot complete pending command", getFullName());
			} finally {
				try {
					if (!client.completePendingCommand()) {
						slog.debug("mcs ftp: logger [{}] failed to complete pending command, reply [{}]", getFullName(),
								client.getReplyString());
					}
				} catch (IOException e) {
					if(e instanceof FTPConnectionClosedException) {
                        slog.debug("logpresso ftp: logger [{}] cannot complete pending command", getFullName());
                        slog.debug("logpresso ftp: exception detail: ", e);
	                }else{
                        slog.error("logpresso ftp: logger [{}] cannot complete pending command: [{}]", getFullName(), e.getMessage());
                        slog.debug("logpresso ftp: exception detail: ", e);
	                }
				}
			}
			
			return new FileInfo(firstLine, 0, fileSize);
		}
	}

	private FTPFile findFile(FTPClient client, String path) throws IOException {
		FTPFile[] files = client.listFiles(path);
		if (files == null || files.length == 0)
			return null;

		return files[0];
	}

	private class FileInfo {
		private String firstLine;
		private long lastPosition;
		private long fileLength;

		public FileInfo(String firstLine, long lastPosition, long lastLength) {
			this.firstLine = firstLine;
			this.lastPosition = lastPosition;
			this.fileLength = lastLength;
		}
	}
}
