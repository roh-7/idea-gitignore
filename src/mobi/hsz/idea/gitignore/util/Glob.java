/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 hsz Jakub Chrzanowski <jakub@hsz.mobi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mobi.hsz.idea.gitignore.util;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.util.containers.ContainerUtil;
import mobi.hsz.idea.gitignore.IgnoreBundle;
import mobi.hsz.idea.gitignore.psi.IgnoreEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Glob util class that prepares glob statements or searches for content using glob rules.
 *
 * @author Jakub Chrzanowski <jakub@hsz.mobi>
 * @since 0.5
 */
public class Glob {
    /** Cache map that holds processed regex statements to the glob rules. */
    private static final WeakHashMap<String, String> GLOBS_CACHE = new WeakHashMap<String, String>();

    /** Cache map that holds compiled regex. */
    private static final WeakHashMap<String, Pattern> PATTERNS_CACHE = new WeakHashMap<String, Pattern>();

    /** Private constructor to prevent creating {@link Glob} instance. */
    private Glob() {
    }

    /**
     * Finds for {@link VirtualFile} list using glob rule in given root directory.
     *
     * @param root  root directory
     * @param entry ignore entry
     * @return search result
     */
    @NotNull
    public static List<VirtualFile> findOne(@NotNull final VirtualFile root, @NotNull IgnoreEntry entry) {
        return find(root, ContainerUtil.newArrayList(entry), false).get(entry);
    }

    /**
     * Finds for {@link VirtualFile} list using glob rule in given root directory.
     *
     * @param root          root directory
     * @param entry         ignore entry
     * @param includeNested attach children to the search result
     * @return search result
     */
    @NotNull
    public static List<VirtualFile> findOne(@NotNull final VirtualFile root, @NotNull IgnoreEntry entry,
                                            final boolean includeNested) {
        return find(root, ContainerUtil.newArrayList(entry), includeNested).get(entry);
    }

    /**
     * Finds for {@link VirtualFile} list using glob rule in given root directory.
     *
     * @param root          root directory
     * @param entries       ignore entries
     * @param includeNested attach children to the search result
     * @return search result
     */
    @NotNull
    public static Map<IgnoreEntry, List<VirtualFile>> find(@NotNull final VirtualFile root,
                                                           @NotNull List<IgnoreEntry> entries,
                                                           final boolean includeNested) {
        final ConcurrentMap<IgnoreEntry, List<VirtualFile>> result = ContainerUtil.newConcurrentMap();
        final HashMap<IgnoreEntry, Matcher> map = ContainerUtil.newHashMap();
        for (IgnoreEntry entry : entries) {
            result.put(entry, ContainerUtil.<VirtualFile>newArrayList());

            final Pattern pattern = createPattern(entry);
            if (pattern == null) {
                continue;
            }
            map.put(entry, pattern.matcher(""));
        }

        VirtualFileVisitor<HashMap<IgnoreEntry, Matcher>> visitor =
                new VirtualFileVisitor<HashMap<IgnoreEntry, Matcher>>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
                    @Override
                    public boolean visitFile(@NotNull VirtualFile file) {
                        final HashMap<IgnoreEntry, Matcher> current = ContainerUtil.newHashMap(getCurrentValue());
                        if (current.isEmpty()) {
                            return false;
                        }

                        final String path = Utils.getRelativePath(root, file);
                        if (path == null || Utils.isVcsDirectory(file)) {
                            return false;
                        }

                        for (Map.Entry<IgnoreEntry, Matcher> item : current.entrySet()) {
                            final Matcher value = item.getValue();
                            boolean matches = false;
                            if (value == null || MatcherUtil.match(value, path)) {
                                matches = true;
                                result.get(item.getKey()).add(file);
                            }
                            if (includeNested && matches) {
                                current.put(item.getKey(), null);
                            }
                        }

                        setValueForChildren(current);
                        return true;
                    }
                };
        visitor.setValueForChildren(map);
        VfsUtil.visitChildrenRecursively(root, visitor);

        return result;
    }

    /**
     * Finds for {@link VirtualFile} paths list using glob rule in given root directory.
     *
     * @param root          root directory
     * @param entries       ignore entry
     * @param includeNested attach children to the search result
     * @return search result
     */
    @NotNull
    public static Map<IgnoreEntry, Set<String>> findAsPaths(@NotNull VirtualFile root,
                                                            @NotNull List<IgnoreEntry> entries, boolean includeNested) {
        final Map<IgnoreEntry, Set<String>> result = ContainerUtil.newHashMap();

        final Map<IgnoreEntry, List<VirtualFile>> files = find(root, entries, includeNested);
        for (Map.Entry<IgnoreEntry, List<VirtualFile>> item : files.entrySet()) {
            final Set<String> set = ContainerUtil.newHashSet();
            for (VirtualFile file : item.getValue()) {
                set.add(Utils.getRelativePath(root, file));
            }
            result.put(item.getKey(), set);
        }

        return result;
    }

    /**
     * Creates regex {@link Pattern} using glob rule.
     *
     * @param rule   rule value
     * @param syntax rule syntax
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull String rule, @NotNull IgnoreBundle.Syntax syntax) {
        return createPattern(rule, syntax, false);
    }

    /**
     * Creates regex {@link Pattern} using glob rule.
     *
     * @param rule           rule value
     * @param syntax         rule syntax
     * @param acceptChildren Matches directory children
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull String rule,
                                        @NotNull IgnoreBundle.Syntax syntax,
                                        boolean acceptChildren) {
        final String regex = syntax.equals(IgnoreBundle.Syntax.GLOB) ? createRegex(rule, acceptChildren) : rule;
        try {
            if (!PATTERNS_CACHE.containsKey(regex)) {
                PATTERNS_CACHE.put(regex, Pattern.compile(regex));
            }
            return PATTERNS_CACHE.get(regex);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * Creates regex {@link Pattern} using {@link IgnoreEntry}.
     *
     * @param entry {@link IgnoreEntry}
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull IgnoreEntry entry) {
        return createPattern(entry, false);
    }

    /**
     * Creates regex {@link Pattern} using {@link IgnoreEntry}.
     *
     * @param entry          {@link IgnoreEntry}
     * @param acceptChildren Matches directory children
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull IgnoreEntry entry, boolean acceptChildren) {
        return createPattern(entry.getValue(), entry.getSyntax(), acceptChildren);
    }

    /**
     * Creates regex {@link String} using glob rule.
     *
     * @param glob           rule
     * @param acceptChildren Matches directory children
     * @return regex {@link String}
     */
    @NotNull
    public static String createRegex(@NotNull String glob, boolean acceptChildren) {
        glob = glob.trim();
        String cached = GLOBS_CACHE.get(glob);
        if (cached != null) {
            return cached;
        }

        StringBuilder sb = new StringBuilder("^");
        boolean escape = false, star = false, doubleStar = false, bracket = false;
        int beginIndex = 0;

        if (StringUtil.startsWith(glob, Constants.DOUBLESTAR)) {
            sb.append("(?:[^/]*?/)*");
            beginIndex = 2;
            doubleStar = true;
        } else if (StringUtil.startsWith(glob, "*/")) {
            sb.append("[^/]*");
            beginIndex = 1;
            star = true;
        } else if (StringUtil.equals(Constants.STAR, glob)) {
            sb.append(".*");
        } else if (StringUtil.startsWithChar(glob, '*')) {
            sb.append(".*?");
        } else if (StringUtil.startsWithChar(glob, '/')) {
            beginIndex = 1;
        } else {
            int slashes = StringUtil.countChars(glob, '/');
            if (slashes == 0 || (slashes == 1 && StringUtil.endsWithChar(glob, '/'))) {
                sb.append("(?:[^/]*?/)*");
            }
        }

        char[] chars = glob.substring(beginIndex).toCharArray();
        for (char ch : chars) {
            if (bracket && ch != ']') {
                sb.append(ch);
                continue;
            } else if (doubleStar) {
                doubleStar = false;
                if (ch == '/') {
                    sb.append("(?:[^/]*/)*?");
                    continue;
                } else {
                    sb.append("[^/]*?");
                }
            }

            if (ch == '*') {
                if (escape) {
                    sb.append("\\*");
                    escape = false;
                    star = false;
                } else if (star) {
                    char prev = sb.length() > 0 ? sb.charAt(sb.length() - 1) : '\0';
                    if (prev == '\0' || prev == '^' || prev == '/') {
                        doubleStar = true;
                    } else {
                        sb.append("[^/]*?");
                    }
                    star = false;
                } else {
                    star = true;
                }
                continue;
            } else if (star) {
                sb.append("[^/]*?");
                star = false;
            }

            switch (ch) {

                case '\\':
                    if (escape) {
                        sb.append("\\\\");
                        escape = false;
                    } else {
                        escape = true;
                    }
                    break;

                case '?':
                    if (escape) {
                        sb.append("\\?");
                        escape = false;
                    } else {
                        sb.append('.');
                    }
                    break;

                case '[':
                    if (escape) {
                        sb.append('\\');
                        escape = false;
                    } else {
                        bracket = true;
                    }
                    sb.append(ch);
                    break;

                case ']':
                    if (!bracket) {
                        sb.append('\\');
                    }
                    sb.append(ch);
                    bracket = false;
                    escape = false;
                    break;

                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    sb.append('\\');
                    sb.append(ch);
                    escape = false;
                    break;

                default:
                    escape = false;
                    sb.append(ch);

            }
        }

        if (star || doubleStar) {
            if (StringUtil.endsWithChar(sb, '/')) {
                sb.append(acceptChildren ? ".+" : "[^/]+/?");
            } else {
                sb.append("[^/]*/?");
            }
        } else {
            if (StringUtil.endsWithChar(sb, '/')) {
                if (acceptChildren) {
                    sb.append("[^/]*");
                }
            } else {
                sb.append(acceptChildren ? "(?:/.*)?" : "/?");
            }
        }

        sb.append('$');
        GLOBS_CACHE.put(glob, sb.toString());

        return sb.toString();
    }

    /** Clears {@link Glob#GLOBS_CACHE} cache. */
    public static void clearCache() {
        GLOBS_CACHE.clear();
        PATTERNS_CACHE.clear();
    }
}
