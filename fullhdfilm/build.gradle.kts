import com.lagradost.cloudstream3.gradle.CloudstreamExtension

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

version = 3

cloudstream {
    authors = listOf("qwert")
    language = "tr"
    description = "FullHDFilmizlesene"
    status = 1
    tvTypes = listOf("Movie")
    iconUrl     = "https://www.fullhdfilmizlesene.now/android-chrome-192x192.png"
}
