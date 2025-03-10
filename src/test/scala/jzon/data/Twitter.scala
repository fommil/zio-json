package jzon.data.twitter

import jzon.{ Decoder, Encoder }

case class Urls(
  url: String,
  expanded_url: String,
  display_url: String,
  indices: List[Int]
)
object Urls {
  implicit val jDecoder: Decoder[Urls] = Decoder.derived
  implicit val jEncoder: Encoder[Urls] = Encoder.derived
}
case class Url(urls: List[Urls])
object Url {
  implicit val jDecoder: Decoder[Url] = Decoder.derived
  implicit val jEncoder: Encoder[Url] = Encoder.derived
}

case class UserEntities(url: Url, description: Url)
object UserEntities {
  implicit val jDecoder: Decoder[UserEntities] = Decoder.derived
  implicit val jEncoder: Encoder[UserEntities] = Encoder.derived
}

case class UserMentions(
  screen_name: String,
  name: String,
  id: Long,
  id_str: String,
  indices: List[Int]
)
object UserMentions {
  implicit val jDecoder: Decoder[UserMentions] = Decoder.derived
  implicit val jEncoder: Encoder[UserMentions] = Encoder.derived
}

case class User(
  id: Long,
  id_str: String,
  name: String,
  screen_name: String,
  location: String,
  description: String,
  url: String,
  entities: UserEntities,
  `protected`: Boolean,
  followers_count: Int,
  friends_count: Int,
  listed_count: Int,
  created_at: String,
  favourites_count: Int,
  utc_offset: Int,
  time_zone: String,
  geo_enabled: Boolean,
  verified: Boolean,
  statuses_count: Int,
  lang: String,
  contributors_enabled: Boolean,
  is_translator: Boolean,
  is_translation_enabled: Boolean,
  profile_background_color: String,
  profile_background_image_url: String,
  profile_background_image_url_https: String,
  profile_background_tile: Boolean,
  profile_image_url: String,
  profile_image_url_https: String,
  profile_banner_url: String,
  profile_link_color: String,
  profile_sidebar_border_color: String,
  profile_sidebar_fill_color: String,
  profile_text_color: String,
  profile_use_background_image: Boolean,
  has_extended_profile: Boolean,
  default_profile: Boolean,
  default_profile_image: Boolean,
  following: Boolean,
  follow_request_sent: Boolean,
  notifications: Boolean,
  translator_type: String
)
object User {
  implicit val jDecoder: Decoder[User] = Decoder.derived
  implicit val jEncoder: Encoder[User] = Encoder.derived
}

case class Entities(
  hashtags: List[String],
  symbols: List[String],
  user_mentions: List[UserMentions],
  urls: List[Urls]
)
object Entities {
  implicit val jDecoder: Decoder[Entities] = Decoder.derived
  implicit val jEncoder: Encoder[Entities] = Encoder.derived
}

case class RetweetedStatus(
  created_at: String,
  id: Long,
  id_str: String,
  text: String,
  truncated: Boolean,
  entities: Entities,
  source: String,
  in_reply_to_status_id: Option[String],
  in_reply_to_status_id_str: Option[String],
  in_reply_to_user_id: Option[String],
  in_reply_to_user_id_str: Option[String],
  in_reply_to_screen_name: Option[String],
  user: User,
  geo: Option[String],
  coordinates: Option[String],
  place: Option[String],
  contributors: Option[String],
  is_quote_status: Boolean,
  retweet_count: Int,
  favorite_count: Int,
  favorited: Boolean,
  retweeted: Boolean,
  possibly_sensitive: Boolean,
  lang: String
)
object RetweetedStatus {
  implicit val jDecoder: Decoder[RetweetedStatus] =
    Decoder.derived
  implicit val jEncoder: Encoder[RetweetedStatus] =
    Encoder.derived
}

case class Tweet(
  created_at: String,
  id: Long,
  id_str: String,
  text: String,
  truncated: Boolean,
  entities: Entities,
  source: String,
  in_reply_to_status_id: Option[String],
  in_reply_to_status_id_str: Option[String],
  in_reply_to_user_id: Option[String],
  in_reply_to_user_id_str: Option[String],
  in_reply_to_screen_name: Option[String],
  user: User,
  geo: Option[String],
  coordinates: Option[String],
  place: Option[String],
  contributors: Option[String],
  retweeted_status: RetweetedStatus,
  is_quote_status: Boolean,
  retweet_count: Int,
  favorite_count: Int,
  favorited: Boolean,
  retweeted: Boolean,
  possibly_sensitive: Boolean,
  lang: String
)

object Tweet {
  implicit val jzonDecoder: Decoder[Tweet] = Decoder.derived
  implicit val jzonEncoder: Encoder[Tweet] = Encoder.derived
}
