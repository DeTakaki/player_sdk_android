package com.sambatech.player.model;

import com.sambatech.player.utils.Helpers;

import java.lang.reflect.Field;

/**
 * Internal extension of the media entity for player/plugins config purposes.
 *
 * @author Leandro Zanol - 2/12/15
 */
public class SambaMediaConfig extends SambaMedia {

	public String id;
	public String projectHash;
	public int projectId;
	public int categoryId;
	public String sessionId = Helpers.getSessionId();
	public int themeColor = 0xFF72BE44;
	public String sttmUrl;
	public String sttmKey;

	@Override
	public String toString() {
		String desc = "";
		Field[] fields = getClass().getSuperclass().getDeclaredFields();

		try {
			for (Field field : fields)
				desc += field.getName() + ": " + field.get(this) + '\n';
		}
		catch (IllegalAccessException e) {
			e.printStackTrace();
		}

		return desc;
	}
}
