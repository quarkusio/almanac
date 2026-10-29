package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.GitSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/// The Quarkus releases never published on Maven Central whose BOM the dataset keeps all the same: the pre-launch
/// releases of December 2018 to March 2019, deployed only to a private repository that no longer exists. A BOM of
/// theirs is resolved from the poms at the release's git tag of github.com/quarkusio/quarkus, copied byte for byte
/// into this jar's resources: the BOM (`bom/pom.xml`) and its parent (`pom.xml`), checked against their git blob ids
/// when read. Parents and imported BOMs from outside the repository come from Maven Central, as for any release.
///
/// Before 0.10.0 the project was named Shamrock, with the groupId `org.jboss.shamrock`. 0.0.1 and 0.1.0 had no BOM
/// (projects gave each Shamrock dependency its version), and 0.9.0 was canceled during its release, so none of them is
/// here.
public final class GitReleases {

    /// A release, its git tag being its version: the commit the tag names and the commit's UTC day, its BOM and the
    /// BOM's parent (`groupId:artifactId`, at the release's version), and the git blob ids of their poms there.
    record Release(String version, String commit, String date, String bom, String bomBlob, String parent,
            String parentBlob) {

        GitSource source() {
            return new GitSource(version, commit, bom);
        }

        String groupId() {
            return bom.substring(0, bom.indexOf(':'));
        }
    }

    private static final String SHAMROCK_BOM = "org.jboss.shamrock:shamrock-bom";
    private static final String SHAMROCK_PARENT = "org.jboss.shamrock:shamrock-parent";

    private static final List<Release> RELEASES = List.of(
            new Release("0.2.0", "97a42b3fc1fd62b8e7a8ba61b9016324e9bcd20c", "2018-12-19",
                    SHAMROCK_BOM, "d1ea532f3f58b2bf9f16f444e8dd17b06f8ecf96",
                    SHAMROCK_PARENT, "f202d45d452c8d85465d5466c8a37fd6aa22ca16"),
            new Release("0.3.0", "4cc3f9a5a99c59edd557a0ee24c2ce34d5200287", "2018-12-26",
                    SHAMROCK_BOM, "4485fa12f221ad117ce5ff0008dab0df31fd2122",
                    SHAMROCK_PARENT, "0374915b6539d9923a96eca0e98b04b3dea2d3e7"),
            new Release("0.4.0", "7f0d51974ce65ba542c78b8dfc82dad3cdc1c534", "2019-01-03",
                    SHAMROCK_BOM, "1c6144573e7daf99e1aefd274e32b05bbb83a812",
                    SHAMROCK_PARENT, "6bd4efe784ba6a056f7be9f429e948a1ef3e25fe"),
            new Release("0.5.0", "9da3f3765157250d69006c2dc3140a3154755bb9", "2019-01-09",
                    SHAMROCK_BOM, "78ef5735e3193a1d42b2d696f380e502f0ebaef9",
                    SHAMROCK_PARENT, "761d726f7c7381fea228e87d1d66fa67d9e7814f"),
            new Release("0.6.0", "60956b81580f0b71cfc426e326ba6447cc070ec0", "2019-01-16",
                    SHAMROCK_BOM, "b6eb2973ff53055285066d79c778f2cee2c5487f",
                    SHAMROCK_PARENT, "3bc9fa8682c4dcaf48aecc6e76c688d0e6f7b893"),
            new Release("0.7.0", "6f59964c352201375fd206098548bf039d71ab2b", "2019-01-23",
                    SHAMROCK_BOM, "1decbee4291bab220e506819dedd8483e047a584",
                    SHAMROCK_PARENT, "83a979affceff58b9a1a8b68f05a31bd9699ac0a"),
            new Release("0.8.0", "6bc09b3962edbbff15e20f32b47d8483cfeaca2c", "2019-02-06",
                    SHAMROCK_BOM, "56502dfb118d4052f028f279e0360c32b88d9303",
                    SHAMROCK_PARENT, "841bb01807b13818affe312ad2acaf2ccf1a410f"),
            new Release("0.9.1", "66ab40ef2921a6dd839ef7d815a3c57c8aeecadd", "2019-02-14",
                    SHAMROCK_BOM, "7357060e75088fbcd758968ee42c5c835a0165da",
                    SHAMROCK_PARENT, "33162163a83256f8855c8c594b0373921d0c9425"),
            new Release("0.10.0", "f26057191b8217f43b632e2eb5b3541821cf242d", "2019-03-01",
                    "io.quarkus:quarkus-bom", "87c262d9b63f1c70ea6dbfad46d4934f26641bd1",
                    "io.quarkus:quarkus-parent", "5a9e46dd92735e771362841621051354e54eaf0e"));

    /// A pre-launch release with nothing for the dataset, which the site lists for reference only: one that had no BOM
    /// (0.0.1 and 0.1.0) or that was canceled during its release (0.9.0, whose 0.9.1 followed the same day).
    ///
    /// @param version its version, which is also its git tag
    /// @param commit  the commit the tag names
    /// @param date    the commit's UTC day
    /// @param why     [#NO_BOM] or [#CANCELED]
    public record Reference(String version, String commit, String date, String why) {}

    /// Why a [Reference] has nothing for the dataset: the release had no BOM.
    public static final String NO_BOM = "no-bom";
    /// Why a [Reference] has nothing for the dataset: the release was canceled.
    public static final String CANCELED = "canceled";

    private static final List<Reference> REFERENCES = List.of(
            new Reference("0.0.1", "1200367b8ddbe5605d8219c4994205f6c1d7af50", "2018-12-12", NO_BOM),
            new Reference("0.1.0", "33dcbe65ecda2cba507d136bed0420e08fe1f42b", "2018-12-13", NO_BOM),
            new Reference("0.9.0", "01ddfa0e920a39b7ddb541408ff5341405c5fe00", "2019-02-14", CANCELED));

    private GitReleases() {
    }

    /// The pre-launch releases listed for reference only, ascending.
    public static List<Reference> references() {
        return REFERENCES;
    }

    /// The versions of these releases, ascending.
    public static List<String> versions() {
        return RELEASES.stream().map(Release::version).toList();
    }

    /// Where the BOM of a release never published on Maven Central comes from; empty for any other version.
    public static Optional<GitSource> source(String version) {
        return release(version).map(Release::source);
    }

    /// The UTC day of the commit that the release's tag names, which stands in for a publication date.
    ///
    /// @throws IllegalArgumentException for a version that is not one of these releases
    public static String date(String version) {
        return release(version).orElseThrow(() -> unknown(version)).date();
    }

    /// The release's BOM pom, as at its git tag.
    ///
    /// @throws IOException when the resource is missing or differs from the tag's pom
    /// @throws IllegalArgumentException for a version that is not one of these releases
    public static byte[] bom(String version) throws IOException {
        Release release = release(version).orElseThrow(() -> unknown(version));
        return read(release, "bom/pom.xml", release.bomBlob());
    }

    /// For [BomResolver.PomSource]: the BOM or its parent of one of these releases, as at its git tag; empty for any
    /// other pom, which the repository serves.
    ///
    /// @throws IOException for another pom of such a release's groupId and version, which was never published either,
    ///                     and when a resource is missing or differs from the tag's pom
    public static Optional<byte[]> pom(String groupId, String artifactId, String version) throws IOException {
        Optional<Release> found = release(version).filter(r -> r.groupId().equals(groupId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Release release = found.get();
        String id = groupId + ":" + artifactId;
        if (id.equals(release.bom())) {
            return Optional.of(read(release, "bom/pom.xml", release.bomBlob()));
        }
        if (id.equals(release.parent())) {
            return Optional.of(read(release, "pom.xml", release.parentBlob()));
        }
        throw new IOException(id + ":" + version + " was never published on Maven Central, and git tag " + version
                + " (commit " + release.commit().substring(0, 7) + ") gives only " + release.bom() + " and its parent "
                + release.parent());
    }

    private static Optional<Release> release(String version) {
        return RELEASES.stream().filter(r -> r.version().equals(version)).findFirst();
    }

    private static IllegalArgumentException unknown(String version) {
        return new IllegalArgumentException(version + " is not a release whose BOM comes from its git tag");
    }

    private static byte[] read(Release release, String path, String blob) throws IOException {
        String resource = "git/" + release.version() + "/" + path;
        byte[] content;
        try (InputStream in = GitReleases.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }
            content = in.readAllBytes();
        }
        String actual = blobId(content);
        if (!actual.equals(blob)) {
            throw new IOException(resource + " is not " + path + " of git tag " + release.version() + " (git blob "
                    + actual + ", the tag's is " + blob + "; were its line ends converted?)");
        }
        return content;
    }

    /// The git blob id of a file's content: SHA-1 of `blob <length>\0` and the bytes.
    static String blobId(byte[] content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(sha1.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
