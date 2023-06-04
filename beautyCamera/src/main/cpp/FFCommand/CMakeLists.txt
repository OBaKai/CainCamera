# 添加源文件
add_library( ffcommand

        SHARED

        cmdutils.c
        ffmpeg.c
        ffmpeg_filter.c
        ffmpeg_opt.c
        main_ffmpeg_utils.cpp)

# 链接静态库
target_link_libraries( ffcommand

                       ffmpeg

                       android
                       jnigraphics
                       log )

