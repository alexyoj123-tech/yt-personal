// Raiz del proyecto. Los plugins se declaran aqui con apply=false y se aplican
// en :app, que es el patron estandar de AGP 8.x.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
