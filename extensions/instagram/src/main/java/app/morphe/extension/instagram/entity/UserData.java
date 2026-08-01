/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.entity;

import com.instagram.common.typedurl.ImageUrl;

public class UserData extends Entity {
    private final Object obj;

    public UserData(Object obj) {
        super(obj);
        this.obj = obj;
    }

    private Object getAdditionalUserInfo() throws Exception {
        return super.getField(this.obj, "fieldName");
    }

    public Boolean isVerified() throws Exception {
        Object additionalUserInfo = getAdditionalUserInfo();
        return (Boolean) super.getMethod(additionalUserInfo, "isVerified");
    }

    public String getUsername() throws Exception {
        Object additionalUserInfo = getAdditionalUserInfo();
        return (String) super.getMethod(additionalUserInfo, "methodName");
    }

    public String getFullName() throws Exception {
        Object additionalUserInfo = getAdditionalUserInfo();
        String name = (String) super.getMethod(additionalUserInfo, "methodName");
        if(name!=null && !name.isEmpty() && name.length()>0){
            return name;
        }
        // Some users don't have fullname, but only username.
        return this.getUsername();
    }

    public String getBio() throws Exception {
        // The literal is a placeholder the patcher rewrites with the accessor name it
        // resolved, so it has to stay the first — and only — string in this method.
        return readBio("BCu");
    }

    /**
     * The biography, asked of both objects a UserData may be wrapping.
     * <p>
     * A profile screen hands us a user whose "additional info" dict answers the
     * accessor with nothing, while the outer object carries the text; elsewhere it is
     * the other way round. Rather than guess which screen we are on, ask both and take
     * the first non-empty answer. An accessor missing from a class is not an error
     * here either — it just means this was not the object holding the bio.
     */
    private String readBio(String accessor) {
        Object[] candidates;
        try {
            candidates = new Object[] { getAdditionalUserInfo(), this.obj };
        } catch (Exception e) {
            candidates = new Object[] { this.obj };
        }
        for (Object target : candidates) {
            if (target == null) continue;
            try {
                Object value = invokeNoArg(target, accessor);
                if (value instanceof String && !((String) value).isEmpty()) {
                    return (String) value;
                }
            } catch (Exception ignored) {
                // wrong object for this accessor — try the next one
            }
        }
        return null;
    }

    /**
     * Calls a no-argument method, looking past the object's own class: obfuscated
     * Instagram models routinely declare an accessor on a superclass or interface,
     * where getDeclaredMethod alone finds nothing.
     */
    private static Object invokeNoArg(Object target, String methodName) throws Exception {
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Method method = cls.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // keep climbing
            }
            for (Class<?> iface : cls.getInterfaces()) {
                try {
                    java.lang.reflect.Method method = iface.getDeclaredMethod(methodName);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (NoSuchMethodException ignored) {
                    // not on this interface either
                }
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    public String getProfilePictureUrl() throws Exception {
        Object additionalUserInfo = getAdditionalUserInfo();
        Object profilePicObject =  super.getMethod(additionalUserInfo, "Bvt");
        if(profilePicObject!=null){
            Entity profilePicEntity = new Entity(profilePicObject);
            return (String) profilePicEntity.getMethod("getUrl");
        }
        return "";
    }

    public ImageUrl getLowResProfilePicture() throws Exception {
        Object additionalUserInfo = getAdditionalUserInfo();
        Object imageUrlObject =  super.getMethod(additionalUserInfo, "mediaName");
        return (ImageUrl) imageUrlObject;
    }

    public String getUserId() throws Exception {
        return (String) super.getMethod(this.obj, "getId");
    }

    public UserFriendshipStatus getUserFriendshipStatus() throws Exception {
        Object additionalUserInfo = getAdditionalUserInfo();
        Object friendshipStatusObject = super.getMethod(additionalUserInfo, "methodname");
        return new UserFriendshipStatus(friendshipStatusObject);
    }

}