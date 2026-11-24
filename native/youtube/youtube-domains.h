#ifndef YOUTUBE_DOMAINS_H
#define YOUTUBE_DOMAINS_H

/*
 * YouTube web, player, media, assets and API host suffixes.
 * Regional redirects are from v2fly/domain-list-community data/youtube at
 * dfa5b1c2258289bdbee70d06352cca23b9bdac7f, checked against Google nameservers.
 * Keep shared Google endpoints explicit: ByeDPI matches TLS hostnames, not URLs.
 */
#define YOUTUBE_DOMAINS \
    "youtube.com youtu.be yt.be youtube youtube-nocookie.com youtubeeducation.com youtubekids.com" \
    " googlevideo.com ytimg.com yt3.ggpht.com yt4.ggpht.com yt3.googleusercontent.com" \
    " youtube.googleapis.com youtubei.googleapis.com youtubeembeddedplayer.googleapis.com jnn-pa.googleapis.com" \
    " wide-youtube.l.google.com youtube-ui.l.google.com yt-video-upload.l.google.com ytimg.l.google.com youtube.l.google.com video.google.com" \
    " www.googleapis.com accounts.google.com www.gstatic.com" \
    " youtube.ae youtube.al youtube.am youtube.at youtube.az" \
    " youtube.ba youtube.be youtube.bg youtube.bh youtube.bo" \
    " youtube.by youtube.ca youtube.cat youtube.ch youtube.cl" \
    " youtube.co youtube.co.ae youtube.co.at youtube.co.cr youtube.co.hu" \
    " youtube.co.id youtube.co.il youtube.co.in youtube.co.jp youtube.co.ke" \
    " youtube.co.kr youtube.co.ma youtube.co.nz youtube.co.th youtube.co.tz" \
    " youtube.co.ug youtube.co.uk youtube.co.ve youtube.co.za youtube.co.zw" \
    " youtube.com.ar youtube.com.au youtube.com.az youtube.com.bd youtube.com.bh" \
    " youtube.com.bo youtube.com.br youtube.com.by youtube.com.co youtube.com.do" \
    " youtube.com.ec youtube.com.ee youtube.com.eg youtube.com.es youtube.com.gh" \
    " youtube.com.gr youtube.com.gt youtube.com.hk youtube.com.hn youtube.com.hr" \
    " youtube.com.jm youtube.com.jo youtube.com.kw youtube.com.lb youtube.com.lv" \
    " youtube.com.ly youtube.com.mk youtube.com.mt youtube.com.mx youtube.com.my" \
    " youtube.com.ng youtube.com.ni youtube.com.om youtube.com.pa youtube.com.pe" \
    " youtube.com.ph youtube.com.pk youtube.com.pt youtube.com.py youtube.com.qa" \
    " youtube.com.ro youtube.com.sa youtube.com.sg youtube.com.sv youtube.com.tn" \
    " youtube.com.tr youtube.com.tw youtube.com.ua youtube.com.uy youtube.com.ve" \
    " youtube.cr youtube.cz youtube.de youtube.dk youtube.ee" \
    " youtube.es youtube.fi youtube.fr youtube.ge youtube.gr" \
    " youtube.gt youtube.hk youtube.hr youtube.hu youtube.ie" \
    " youtube.in youtube.iq youtube.is youtube.it youtube.jo" \
    " youtube.jp youtube.kr youtube.kz youtube.la youtube.lk" \
    " youtube.lt youtube.lu youtube.lv youtube.ly youtube.ma" \
    " youtube.md youtube.me youtube.mk youtube.mn youtube.mx" \
    " youtube.my youtube.ng youtube.ni youtube.nl youtube.no" \
    " youtube.pa youtube.pe youtube.ph youtube.pk youtube.pl" \
    " youtube.pr youtube.pt youtube.qa youtube.ro youtube.rs" \
    " youtube.ru youtube.sa youtube.se youtube.sg youtube.si" \
    " youtube.sk youtube.sn youtube.soy youtube.sv youtube.tn" \
    " youtube.tv youtube.ua youtube.ug youtube.uy youtube.vn"

/*
 * Discord's Core host suffixes. The attachment upload host is exact-only and
 * uses the leading-dot marker handled by the ByeDPI host matcher.
 * Source: v2fly/domain-list-community data/discord.
 */
#define DISCORD_DOMAINS \
    "dis.gd discord.co discord.com discord.design discord.dev discord.gg" \
    " discord.gift discord.gifts discord.media discord.new discord.store" \
    " discord.tools discord-activities.com discordactivities.com" \
    " discordapp.com discordapp.net discordmerch.com discordpartygames.com" \
    " discordsays.com discordstatus.com"

#define DISCORD_EXACT_HOSTS \
    ".discord-attachments-uploads-prd.storage.googleapis.com"

#endif
