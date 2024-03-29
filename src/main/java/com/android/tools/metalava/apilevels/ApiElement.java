/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.metalava.apilevels;

import org.jetbrains.annotations.NotNull;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an API element, e.g. class, method or field.
 */
public class ApiElement implements Comparable<ApiElement> {
    public static final int NEVER = Integer.MAX_VALUE;

    private final String mName;

    /**
     * The Android platform SDK version this API was first introduced in.
     */
    private int mSince;

    /**
     * The Android extension SDK version this API was first introduced in.
     */
    private int mSinceExtension = NEVER;

    /**
     * The SDKs and their versions this API was first introduced in.
     *
     * The value is a comma-separated list of &lt;int&gt;:&lt;int&gt; values, where the first
     * &lt;int&gt; is the integer ID of an SDK, and the second &lt;int&gt; the version of that SDK,
     * in which this API first appeared.
     *
     * This field is a super-set of mSince, and if non-null/non-empty, should be preferred.
     */
    private String mSdks;

    private String mMainlineModule;
    private int mDeprecatedIn;
    private int mLastPresentIn;

    /**
     * @param name       the name of the API element
     * @param version    an API version for which the API element existed, or -1 if the class does
     *                   not yet exist in the Android SDK (only in extension SDKs)
     * @param deprecated whether the API element was deprecated in the API version in question
     */
    ApiElement(String name, int version, boolean deprecated) {
        assert name != null;
        mName = name;
        mSince = version;
        mLastPresentIn = version;
        if (deprecated) {
            mDeprecatedIn = version;
        }
    }

    /**
     * @param name    the name of the API element
     * @param version an API version for which the API element existed
     */
    ApiElement(String name, int version) {
        this(name, version, false);
    }

    ApiElement(String name) {
        assert name != null;
        mName = name;
    }

    /**
     * Returns the name of the API element.
     */
    public final String getName() {
        return mName;
    }

    /**
     * The Android API level of this ApiElement.
     */
    public int getSince() {
        return mSince;
    }

    /**
     * The extension version of this ApiElement.
     */
    public int getSinceExtension() {
        return mSinceExtension;
    }

    /**
     * Checks if this API element was introduced not later than another API element.
     *
     * @param other the API element to compare to
     * @return true if this API element was introduced not later than {@code other}
     */
    final boolean introducedNotLaterThan(ApiElement other) {
        return mSince <= other.mSince;
    }

    /**
     * Updates the API element with information for a specific API version.
     *
     * @param version    an API version for which the API element existed
     * @param deprecated whether the API element was deprecated in the API version in question
     */
    void update(int version, boolean deprecated) {
        assert version > 0;
        if (mSince > version) {
            mSince = version;
        }
        if (mLastPresentIn < version) {
            mLastPresentIn = version;
        }
        if (deprecated) {
            if (mDeprecatedIn == 0 || mDeprecatedIn > version) {
                mDeprecatedIn = version;
            }
        }
    }

    /**
     * Updates the API element with information for a specific API version.
     *
     * @param version an API version for which the API element existed
     */
    public void update(int version) {
        update(version, isDeprecated());
    }

    /**
     * Analoguous to update(), but for extensions sdk versions.
     *
     * @param version an extension SDK version for which the API element existed
     */
    public void updateExtension(int version) {
        assert version > 0;
        if (mSinceExtension > version) {
            mSinceExtension = version;
        }
    }

    public void updateSdks(String sdks) { mSdks = sdks; }

    public void updateMainlineModule(String module) { mMainlineModule = module; }

    public String getMainlineModule() { return mMainlineModule; }

    /**
     * Checks whether the API element is deprecated or not.
     */
    public final boolean isDeprecated() {
        return mDeprecatedIn != 0;
    }

    /**
     * Prints an XML representation of the element to a stream terminated by a line break.
     * Attributes with values matching the parent API element are omitted.
     *
     * @param tag           the tag of the XML element
     * @param parentElement the parent API element
     * @param indent        the whitespace prefix to insert before the XML element
     * @param stream        the stream to print the XML element to
     */
    void print(String tag, ApiElement parentElement, String indent, PrintStream stream) {
        print(tag, true, parentElement, indent, stream);
    }

    /**
     * Prints an XML representation of the element to a stream terminated by a line break.
     * Attributes with values matching the parent API element are omitted.
     *
     * @param tag           the tag of the XML element
     * @param closeTag      if true the XML element is terminated by "/>", otherwise the closing
     *                      tag of the element is not printed
     * @param parentElement the parent API element
     * @param indent        the whitespace prefix to insert before the XML element
     * @param stream        the stream to print the XML element to
     * @see #printClosingTag(String, String, PrintStream)
     */
    void print(String tag, boolean closeTag, ApiElement parentElement, String indent,
               PrintStream stream) {
        stream.print(indent);
        stream.print('<');
        stream.print(tag);
        stream.print(" name=\"");
        stream.print(encodeAttribute(mName));
        if (!isEmpty(mMainlineModule) && !isEmpty(mSdks)) {
            stream.print("\" module=\"");
            stream.print(encodeAttribute(mMainlineModule));
        }
        if (mSince > parentElement.mSince) {
            stream.print("\" since=\"");
            stream.print(mSince);
        }
        if (!isEmpty(mSdks) && !Objects.equals(mSdks, parentElement.mSdks)) {
            stream.print("\" sdks=\"");
            stream.print(mSdks);
        }
        if (mDeprecatedIn != 0) {
            stream.print("\" deprecated=\"");
            stream.print(mDeprecatedIn);
        }
        if (mLastPresentIn < parentElement.mLastPresentIn) {
            stream.print("\" removed=\"");
            stream.print(mLastPresentIn + 1);
        }
        stream.print('"');
        if (closeTag) {
            stream.print('/');
        }
        stream.println('>');
    }

    private boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * Prints homogeneous XML elements to a stream. Each element is printed on a separate line.
     * Attributes with values matching the parent API element are omitted.
     *
     * @param elements the elements to print
     * @param tag      the tag of the XML elements
     * @param indent   the whitespace prefix to insert before each XML element
     * @param stream   the stream to print the XML elements to
     */
    void print(Collection<? extends ApiElement> elements, String tag, String indent, PrintStream stream) {
        for (ApiElement element : sortedList(elements)) {
            element.print(tag, this, indent, stream);
        }
    }

    private <T extends ApiElement> List<T> sortedList(Collection<T> elements) {
        List<T> list = new ArrayList<>(elements);
        Collections.sort(list);
        return list;
    }

    /**
     * Prints a closing tag of an XML element terminated by a line break.
     *
     * @param tag    the tag of the element
     * @param indent the whitespace prefix to insert before the closing tag
     * @param stream the stream to print the XML element to
     */
    static void printClosingTag(String tag, String indent, PrintStream stream) {
        stream.print(indent);
        stream.print("</");
        stream.print(tag);
        stream.println('>');
    }

    private static String encodeAttribute(String attribute) {
        StringBuilder sb = new StringBuilder();
        int n = attribute.length();
        // &, ", ' and < are illegal in attributes; see http://www.w3.org/TR/REC-xml/#NT-AttValue
        // (' legal in a " string and " is legal in a ' string but here we'll stay on the safe side).
        for (int i = 0; i < n; i++) {
            char c = attribute.charAt(i);
            if (c == '"') {
                sb.append("&quot;"); //$NON-NLS-1$
            } else if (c == '<') {
                sb.append("&lt;"); //$NON-NLS-1$
            } else if (c == '\'') {
                sb.append("&apos;"); //$NON-NLS-1$
            } else if (c == '&') {
                sb.append("&amp;"); //$NON-NLS-1$
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    @Override
    public int compareTo(@NotNull ApiElement other) {
        return mName.compareTo(other.mName);
    }
}
