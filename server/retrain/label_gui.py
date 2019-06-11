import tkinter as tk
from PIL import ImageTk, Image
import os
import sys

# Labels
labels = {}
img_num = 1

# Create window
window = tk.Tk()
window.title("Image Labeler")
window.geometry("400x400")
window.configure(background='white')

# Add image to panel
path = "images/pic{}.png".format(img_num)
img = ImageTk.PhotoImage(Image.open(path))
panel = tk.Label(window, image = img)

# Add text input
txtVar = tk.StringVar(None)
usrIn = tk.Entry(window, textvariable = txtVar, width = 90)
usrIn.grid(row = 50, column = 60)

def retrieve_input(optional="ok"):
    global img_num

    # Get input value
    inputValue=usrIn.get()
    labels["images/pic{}.png".format(img_num)] = inputValue
    usrIn.delete(0, 'end')
    print(img_num)

    # Update image
    img_num += 1
    path = "images/pic{}.png".format(img_num)
    if not os.path.exists(path):
        print(path)
        print("Done!")
        with open('labels.txt', 'w') as fout:
            fout.write(str(labels))
        sys.exit(0)

    img2 = ImageTk.PhotoImage(Image.open(path))
    panel.configure(image=img2)
    panel.image = img2

def goback_one():
    # Update image
    global img_num
    img_num -= 1
    path = "images/pic{}.png".format(img_num)
    img2 = ImageTk.PhotoImage(Image.open(path))
    panel.configure(image=img2)
    panel.image = img2

# Add submit button
window.bind('<Return>', retrieve_input)
buttonCommit=tk.Button(window, height=1, width=10, text="Commit", command=lambda: retrieve_input())
buttonPrev=tk.Button(window, height=1, width=10, text="Previous", command=lambda: goback_one())

# Display
usrIn.pack()
panel.pack()
buttonCommit.pack()
buttonPrev.pack()
window.mainloop()
