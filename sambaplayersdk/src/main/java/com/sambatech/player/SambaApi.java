package com.sambatech.player;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.Log;

import com.sambatech.player.event.SambaApiCallback;
import com.sambatech.player.model.SambaMedia;
import com.sambatech.player.model.SambaMediaConfig;
import com.sambatech.player.model.SambaMediaRequest;
import com.sambatech.player.utils.Helpers;

import org.jose4j.base64url.internal.apache.commons.codec.binary.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Manages media data request from server.
 *
 * @author Thiago Miranda, Leandro Zanol - 2/12/15
 */
public class SambaApi {

	private Activity activity;
	private String accessToken;

	/**
	 * Output map
	 */
	private static final Map<String, Integer> outputMap = new HashMap<String, Integer>() {{
		put("_raw", -1);
		put("abr_hls", 0);
		put("240p", 1);
		put("360p", 2);
		put("480p", 3);
		put("720p", 4);
		put("1080p", 5);
	}};

	/**
	 * SambaApi constructor
	 *
	 * @param activity Reference to the current Activity
	 * @param accessToken Configured SambaTech access token (ignored for now, pass an empty string or null)
	 */
	public SambaApi(Activity activity, String accessToken) {
		//TODO validar token
		this.activity = activity;
		this.accessToken = accessToken;
	}

	/**
	 * Requests a media from server.
	 *
	 * @param request Request data
	 * @param callback Listener for server media response
	 */
	public void requestMedia(SambaMediaRequest request, SambaApiCallback callback) {
		new RequestMediaTask(callback).execute(request);
	}

	/**
	 * Requests several medias from server.
	 *
	 * @param requests Several request data
	 * @param callback Listener for server media response
	 */
	public void requestMedia(final SambaMediaRequest[] requests, final SambaApiCallback callback) {
		SambaApiCallback callbackReq = new SambaApiCallback() {
			private int counter = 0;
			private List<SambaMedia> mediaList = new ArrayList<>();

			@Override
			public void onMediaResponse(SambaMedia media) {
				callback.onMediaResponse(media);
				mediaList.add(media);
				checkLast();
			}

			@Override
			public void onMediaResponseError(String msg, SambaMediaRequest request) {
				callback.onMediaResponseError(msg, request);
				checkLast();
			}

			private void checkLast() {
				if (++counter == requests.length)
					callback.onMediaListResponse(mediaList.toArray(new SambaMedia[mediaList.size()]));
			}
		};

		for (SambaMediaRequest req : requests)
			requestMedia(req, callbackReq);
	}

	/**
	 * Asynchronous request to the Samba Player API. Retrieves the media.
	 */
	private class RequestMediaTask extends AsyncTask<SambaMediaRequest, Void, SambaMedia> {
		private SambaApiCallback listener;
		private SambaMediaRequest request;
		private String errorMsg;

		public RequestMediaTask(SambaApiCallback listener) {
			this.listener = listener;
		}

		@Override
		protected SambaMedia doInBackground(SambaMediaRequest... params) {
			request = params[0];
			int delimiter = request.mediaId != null ? Integer.parseInt(request.mediaId.split("(?=\\d[a-zA-Z]*$)")[1].substring(0, 1)) : 0;

			String url = activity.getString(R.string.player_endpoint) + request.projectHash +
					(request.mediaId != null ? "/" + request.mediaId : "?" +
							(request.streamUrls.length > 0 ? "alternateLive=" + request.streamUrls[0] : "streamName=" + request.streamName));

			InputStream inputStream = null;
			Scanner scanner = null;
			Scanner scannerDelimited = null;

			errorMsg = "Failed to load media data";

			try {
				inputStream = new URL(url).openStream();
				scanner = new Scanner(inputStream);
				scannerDelimited = scanner.useDelimiter("\\A");

				if (scannerDelimited.hasNext()) {
					String token = scannerDelimited.next();

					token = token.substring(delimiter, token.length() - delimiter).replaceAll("\\-", "+").replaceAll("_", "/");

					switch (token.length()%4) {
						case 0:
							break;
						case 2:
							token += "==";
							break;
						case 3:
							token += "=";
							break;
						default:
					}

					return parseMedia(new JSONObject(new String(Base64.decodeBase64(token))));
				}
			}
			catch (Exception e) {
				errorMsg = "Error opening server request";
				Log.w(getClass().getSimpleName(), errorMsg, e);
			}
			finally {
				try {
					if (inputStream != null)
						inputStream.close();

					if (scanner != null)
						scanner.close();

					if (scannerDelimited != null)
						scannerDelimited.close();
				}
				catch (IOException e) {
					errorMsg = "Error closing server request";
					Log.w(getClass().getSimpleName(), errorMsg, e);
				}
			}

			return null;
		}

		/**
		 * Triggered after the Samba Player API success response
		 * @param media Samba Media
		 */
		@Override
		protected void onPostExecute(SambaMedia media) {
			if (media == null) {
				listener.onMediaResponseError(errorMsg, request);
				return;
			}

			listener.onMediaResponse(media);
		}

		/**
		 * Get the json response and serializes it
		 * @param json Json Response
		 * @return Samba Media object
		 */
		private SambaMedia parseMedia(JSONObject json) {
			try {
				String qualifier = json.getString("qualifier").toLowerCase();

				if (!qualifier.equals("video") && !qualifier.equals("live"))
					return null;

				SambaMediaConfig media = new SambaMediaConfig();
				ArrayList<SambaMedia.Output> outputArray = new ArrayList<>();

				JSONObject playerConfig = json.getJSONObject("playerConfig");
				JSONObject apiConfig = json.getJSONObject("apiConfig");
				JSONObject projectConfig = json.getJSONObject("project");

				media.projectHash = projectConfig.getString("playerHash");
				media.projectId = projectConfig.getInt("id");
				media.title = json.getString("title");
				media.outputs = new ArrayList<>();

				if (json.has("id"))
					media.id = json.getString("id");

				if (json.has("categoryId"))
					media.categoryId = json.getInt("categoryId");

				if (json.has("deliveryRules")) {
					String defaultOutput = json.getJSONObject("project").getString("defaultOutput");
					JSONArray rules = json.getJSONArray("deliveryRules");
					int totalRules = rules.length();
					JSONObject rule;
					JSONArray outputs;
					JSONObject output;
					String defaultOutputCurrent;

					// looks for HLS delivery or the last taken
					ArrayList<String> filledRules = new ArrayList<>();
					for (int i = 0; i < totalRules; ++i) {
						rule = rules.getJSONObject(i);
						media.type = rule.getString("urlType").toLowerCase();

						if(filledRules.contains(media.type)) continue;

						if (!media.type.equals("hls") && !media.type.equals("progressive"))
							continue;

						outputs = rule.getJSONArray("outputs");

						defaultOutputCurrent = media.type.equals("hls") ? "abr_hls" : defaultOutput;

						for (int j = outputs.length(); j-- > 0;) {
							output = outputs.getJSONObject(j);
							String label = output.getString("outputName");

							SambaMedia.Output cOutput = new SambaMedia.Output();
							cOutput.url = output.getString("url");
							cOutput.label = (output.getString("outputName").equals("abr_hls")) ? "auto" : output.getString("outputName");
							cOutput.position = outputMap.get(output.getString("outputName").toLowerCase());

							//TODO checar comportamento de projeto sem default output
							if (!label.equalsIgnoreCase("_raw") && !output.isNull("url") &&
									(label.equals(defaultOutputCurrent))) {

								// if HLS (MBR), set to exit loop
								if (media.type.equals("hls"))
									i = totalRules;
								media.url = output.getString("url");
								cOutput.current = true;
								media.outputs.add(cOutput);
							}else if(!label.equalsIgnoreCase("_raw") && !output.isNull("url")){
								media.outputs.add(cOutput);
							}
						}

						//TODO retirar o media.url
						if(media.url == null) {
							media.url = media.outputs.get(0).url;
						}

						filledRules.add(media.type);
					}
					sortOutputs(media.outputs);

				}
				else if (json.has("liveOutput")) {
					media.url = json.getJSONObject("liveOutput").getString("baseUrl");
					media.isLive = true;

					// media type relies on URL
					if (media.url.contains(".m3u8"))
						media.type = "hls";
				}

				JSONArray thumbs = json.optJSONArray("thumbnails");

				if (thumbs.length() > 0 && !thumbs.getJSONObject(0).isNull("url")) {
					/*Bitmap bmp = BitmapFactory.decodeStream(new URL(thumbs.getJSONObject(0).getString("url")).openStream());
					bmp.setDensity(Bitmap.DENSITY_NONE);
					media.thumb = new BitmapDrawable(activity.getResources(), bmp);*/
					media.thumb = Drawable.createFromStream(new URL(thumbs.getJSONObject(0).getString("url")).openStream(), "Thumbnail");
				}
				//else media.thumb = ContextCompat.getDrawable(activity, R.drawable.thumb);

				if (playerConfig.has("theme") && !playerConfig.getString("theme").toLowerCase().equals("default"))
					media.themeColor = (int)Long.parseLong("FF" + playerConfig.getString("theme").replaceAll("^#*", ""), 16);
				if (apiConfig.has("sttm")) {
					JSONObject sttm = apiConfig.getJSONObject("sttm");
					media.sttmUrl = sttm.optString("url", "http://sttm.sambatech.com.br/collector/__sttm.gif");
					media.sttmKey = sttm.optString("key", "ae810ebc7f0654c4fadc50935adcf5ec");
				}

				return media;
			}
			catch (Exception e) {
				Log.e(getClass().getSimpleName(), "Failed to search media", e);
			}

			return null;
		}

		/**
		 * Sort the outputs
		 * @param outputs Current media outputs
		 */
		private void sortOutputs(ArrayList<SambaMedia.Output> outputs) {
			Collections.sort(outputs, new Helpers.CustomSorter());
		}
	}
}
