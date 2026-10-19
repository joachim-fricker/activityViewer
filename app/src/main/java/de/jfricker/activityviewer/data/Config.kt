package de.jfricker.activityviewer.data

object Config {
    private const val OWNER = "joachim-fricker"
    private const val REPO = "myactivities"
    const val BRANCH = "main"
    const val WORKFLOW_FILE = "main.yml"

    const val RAW_BASE = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/activities"
    const val API_BASE = "https://api.github.com/repos/$OWNER/$REPO"
}
